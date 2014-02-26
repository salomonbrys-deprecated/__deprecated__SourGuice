package com.github.sourguice.controller;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.CharBuffer;
import java.util.LinkedList;
import java.util.regex.MatchResult;

import javax.annotation.CheckForNull;
import javax.inject.Inject;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.github.sourguice.annotation.controller.HttpError;
import com.github.sourguice.annotation.controller.Redirects;
import com.github.sourguice.call.impl.MvcCallerImpl;
import com.github.sourguice.request.wrapper.NoJsessionidHttpRequest;
import com.github.sourguice.throwable.invocation.HandledException;
import com.github.sourguice.throwable.invocation.NoSuchRequestParameterException;
import com.github.sourguice.utils.RequestScopeContainer;
import com.google.inject.Injector;

/**
 * Servlet that will handle a request and transmit it to the relevant controller's invocation
 * As each controller is registered to a URL pattern, for each URL pattern there is a  ControllersServlet.
 * One ControllersServlet may have multiple controllers if multiple controllers are registered on the same URL pattern.
 *
 * @author Salomon BRYS <salomon.brys@gmail.com>
 */
public final class ControllersServlet extends HttpServlet {
	@SuppressWarnings("javadoc")
	private static final long serialVersionUID = -644345902222937570L;

	/**
	 * List of {@link ControllerHandler}s registered for the path that this servlet handles
	 */
	LinkedList<ControllerHandler<?>> handlers = new LinkedList<>();

	/**
	 * The guice injector that will be used to get different SourGuice implementations
	 */
	@CheckForNull private Injector injector;

	/**
	 * Method for injecting the injector
	 *
	 * @param injector The guice injector that will be used to get different SourGuice implementations
	 */
	@Inject
	public void setInjector(Injector injector) {
		this.injector = injector;
	}

	/**
	 * Adds a controller to this servlet's path
	 * This means that the given controller is registered on the same path as the servlet
	 *
	 * @param c The controller to add to this servlet handlers
	 */
	public <T> void addController(ControllerHandler<T> c) {
		handlers.add(c);
	}

	/**
	 * Serves a request
	 * This will ask all its controllers for the best invocation and will select the best of them all
	 * It will than invoke the invocation and render the corresponding view if necessary
	 *
	 * @param req The HTTP Request
	 * @param res The HTTP Response
	 * @throws ServletException When an exception that was not handled by the MVC system is thrown
	 * @throws IOException If an input or output exception occurs
	 */
	private void serve(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException {

		assert req != null;
		assert res != null;
		assert injector != null;

		// Removes JSESSIONID from the request path if it is there
		if (req.getPathInfo() != null)
			req = new NoJsessionidHttpRequest(req);

		RequestScopeContainer requestScopeContainer = injector.getInstance(RequestScopeContainer.class);

		// Stores the request into the RequestScoped Container so it can be later retrieved using @GuiceRequest
		requestScopeContainer.store(HttpServletRequest.class, req);

		// Gets the best invocation of all controller handlers
		ControllerInvocationInfos infos = null;
		for (ControllerHandler<?> handler : this.handlers)
			infos = ControllerInvocationInfos.GetBest(infos, handler.getBestInvocation(req));

		// If no invocation were found
		if (infos == null) {
			// Sends a 404 otherwise
			res.sendError(404);
			return ;
		}

		assert infos.urlMatch != null;
		// Stores the MatchResult into the RequestScoped Container so it can be later retrieved with guice injection
		requestScopeContainer.store(MatchResult.class, infos.urlMatch);

		try {
			// Invoke the invocation using the MethodCaller registered in Guice
			// TODO: ThrownWhenHandled should definitively be FALSE !
			Object ret = injector.getInstance(MvcCallerImpl.class).call(infos.invocation, infos.urlMatch, true);

			// Sets the view to the default default view
			String view = infos.defaultView;

			//TODO: No reflexivity :)

			if (view == null) {
				HttpError sendsError = infos.invocation.getMethod().getAnnotation(HttpError.class);
				if (sendsError != null) {
					int code = sendsError.value();
					String message = sendsError.message();
					if (ret != null) {
						if (ret instanceof Integer)
							code = ((Integer)ret).intValue();
						else
							message = ret.toString();
					}
					if (message.isEmpty())
						res.sendError(code);
					else
						res.sendError(code, message);
					return ;
				}

				Redirects redirectsTo = infos.invocation.getMethod().getAnnotation(Redirects.class);
				if (redirectsTo != null) {
					String to = redirectsTo.value();
					if (ret != null) {
						if (to.isEmpty())
							to = ret.toString();
						else if (to.contains("{}"))
							to = to.replace("{}", ret.toString());
					}
					if (!to.isEmpty()) {
						res.sendRedirect(to);
						return ;
					}
				}

				if (infos.invocation.getWrites() != null) {
					if (ret == null)
						throw new RuntimeException("@Writes annotated method must NOT return null");
					if (ret instanceof InputStream)
						ret = new InputStreamReader((InputStream)ret);
					if (ret instanceof Readable) {
						Readable r = (Readable)ret;
						CharBuffer cb = CharBuffer.allocate(infos.invocation.getWrites().bufferSize());
						while (r.read(cb) >= 0) {
							cb.flip();
							res.getWriter().append(cb);
							cb.clear();
						}
					}
					else
						res.getWriter().write(ret.toString());
					if (ret instanceof Closeable)
						((Closeable)ret).close();
					return ;
				}
			}

			// If the method returned a view, sets the view to it
			if (ret != null) {
				if (view != null && view.contains("{}"))
					view = view.replace("{}", ret.toString());
				else
					view = ret.toString();
			}

			// If there is a view to display
			if (view != null)
				infos.invocation.getController().renderView(view, injector);
		}
		catch (NoSuchRequestParameterException e) {
			// If a parameter is missing from the request, sends a 400 error
			res.sendError(400, e.getMessage());
		}
		catch (HandledException e) {
			// Nothing here, exception was handled by the MvcExceptionService, it is safe (and expected) to ignore
		}
		catch (Throwable thrown) {
			// When any other exception is thrown, encapsulates it into a ServletException
			throw new ServletException(thrown);
		}
	}

	@Override protected void doGet(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException {
		serve(req, res);
	}

	@Override protected void doPost(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException {
		serve(req, res);
	}

	@Override protected void doPut(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException {
		serve(req, res);
	}

	@Override protected void doDelete(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException {
		serve(req, res);
	}

	@Override protected void doHead(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException {
		serve(req, res);
	}

	@Override protected void doOptions(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException {
		serve(req, res);
	}

	@Override protected void doTrace(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException {
		serve(req, res);
	}
}
