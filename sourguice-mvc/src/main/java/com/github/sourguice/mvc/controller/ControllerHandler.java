package com.github.sourguice.mvc.controller;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import javax.annotation.CheckForNull;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.servlet.http.HttpServletRequest;

import com.github.sourguice.call.SGInvocationFactory;
import com.github.sourguice.mvc.SourGuiceMvc;
import com.github.sourguice.mvc.annotation.controller.ViewDirectory;
import com.github.sourguice.mvc.annotation.controller.ViewRendered;
import com.github.sourguice.mvc.annotation.controller.ViewRenderedWith;
import com.github.sourguice.mvc.annotation.request.RequestMapping;
import com.github.sourguice.mvc.view.Model;
import com.github.sourguice.mvc.view.NoViewRendererException;
import com.github.sourguice.mvc.view.ViewRenderer;
import com.github.sourguice.mvc.view.ViewRendererService;
import com.github.sourguice.mvc.view.ViewRenderingException;
import com.github.sourguice.provider.GuiceTypedProvider;
import com.github.sourguice.provider.TypedProvider;
import com.github.sourguice.utils.Annotations;
import com.google.inject.Binder;
import com.google.inject.Key;
import com.google.inject.MembersInjector;
import com.google.inject.TypeLiteral;

/**
 * Handles a controller class.
 * A controller class can be any class that is declared in {@link SourGuiceMvc} configureControllers method
 * using the syntax control(pattern).with(controller.class)
 * This class is responsible for creating and managing all possible invocations for the given class
 *   (which are all methods annotated with @{@link RequestMapping})
 *
 * @author Salomon BRYS <salomon.brys@gmail.com>
 * @param <T> The controller class to handle
 */
public final class ControllerHandler<T> implements TypedProvider<T> {
    /**
     * The Class object of the controller class to handle
     */
    private final TypedProvider<T> controller;

    /**
     * List of available invocations for this controller
     */
    private final Map<Method, ControllerInvocation> invocations = new HashMap<>();

    /**
     * The default view directory, not empty if the controller is annotated with {@link ViewDirectory}
     */
    private String viewDirectory = "";

    /**
     * List of view renderers associated to this controller. Not empty if the controller is annotated with {@link ViewRendered}
     */
    private ViewRenderedWith[] viewRenderers = {};

    /**
     * Cache that associate a view to a renderer, so each view will look for its renderer only once
     */
    private final Map<String, TypedProvider<? extends ViewRenderer>> rendererCache = new ConcurrentHashMap<>();

    /**
     * Provider for the {@link ViewRendererService}
     */
    @Inject
    private @CheckForNull Provider<ViewRendererService> viewRendererServiceProvider;

    /**
     * Provider for the request's {@link Model}
     */
    @Inject
    private @CheckForNull Provider<Model> modelProvider;

    /**
     * Injects {@link GuiceTypedProvider}'s members
     */
    @Inject
    private @CheckForNull MembersInjector<GuiceTypedProvider<?>> getterInjector;

    /**
     * Constructor
     *
     * @param controller The controller getter to handle
     * @param binder Used to request injection on {@link ControllerInvocation}s and on this.
     * @param invocationFactory The factory responsible for creating new invocations
     */
    public ControllerHandler(final TypedProvider<T> controller, final Binder binder, final SGInvocationFactory invocationFactory) {
        this.controller = controller;

        final ViewDirectory vdAnno = Annotations.getOneTreeRecursive(ViewDirectory.class, controller.getTypeLiteral().getRawType());
        if (vdAnno != null) {
            this.viewDirectory = vdAnno.value();
        }

        final ViewRendered rdAnno = Annotations.getOneTreeRecursive(ViewRendered.class, controller.getTypeLiteral().getRawType());
        if (rdAnno == null) {
            final ViewRenderedWith rdwAnno = Annotations.getOneTreeRecursive(ViewRenderedWith.class, controller.getTypeLiteral().getRawType());
            if (rdwAnno != null) {
                this.viewRenderers = new ViewRenderedWith[] { rdwAnno };
            }
        }
        else {
            this.viewRenderers = rdAnno.value();
        }

        for (final Method method : controller.getTypeLiteral().getRawType().getMethods()) {
        	final RequestMapping mapping = Annotations.getOneRecursive(RequestMapping.class, method.getAnnotations());
            if (mapping != null) {
            	ControllerInvocation invocation = new ControllerInvocation(this, mapping, method, invocationFactory);
            	binder.requestInjection(invocation);
                this.invocations.put(method, invocation);
            }
        }

        binder.requestInjection(this);
    }

    /**
     * Gets the best invocation of all the invocable methods of this controller for this request
     *
     * @param req The request to get invocation for
     * @return All infos opf the best invocation
     */
    public @CheckForNull ControllerInvocationInfos getBestInvocation(final HttpServletRequest req) {
        // Get the best invocation for the given request
        ControllerInvocationInfos infos = null;
        for (final ControllerInvocation invocation : this.invocations.values()) {
            infos = ControllerInvocationInfos.getBest(infos, invocation.canServe(req));
        }

        return infos;
    }

    /**
     * @param method The method to find the invocation
     * @return The invocations that were found on this controller class for this method
     * @throws UnsupportedOperationException If the method has no invocation on this class
     */
	public ControllerInvocation getInvocations(final Method method) throws UnsupportedOperationException {
		final ControllerInvocation ret = this.invocations.get(method);
		if (ret == null) {
			throw new UnsupportedOperationException("No such method @Callable " + getTypeLiteral().getRawType().getCanonicalName() + "." + method.toString());
		}
		return ret;
	}

    /**
     * Renders a specific view with the current request and model informations
     *
     * @param view The view to render
     * @throws NoViewRendererException When no view renderer has been found for this view
     * @throws ViewRenderingException If anything went wrong during rendering
     * @throws IOException IO failure
     */
    public void renderView(String view) throws NoViewRendererException, ViewRenderingException, IOException {

        // If a view directory were set, prefixes the view with it
        if (view.charAt(0) != '/' && !this.viewDirectory.isEmpty()) {
            view = this.viewDirectory + "/" + view;
        }

        // Maybe it has already been set, so we look for it
        TypedProvider<? extends ViewRenderer> renderer = this.rendererCache.get(view);

        // If it has not been set, we need to set it only once, so we get a synchronized lock
        if (renderer == null) {
            synchronized (this) {
                // Maybe it has been set while we were waiting for the lock, so we check again
                renderer = this.rendererCache.get(view);
                if (renderer == null) {
                    // Gets the view renderer either from the controller class or from Guice
                    for (final ViewRenderedWith rdw : this.viewRenderers) {
                        if (Pattern.matches(rdw.regex(), view)) {
                            renderer = new GuiceTypedProvider<>(Key.get(rdw.renderer()));
                            assert this.getterInjector != null;
							this.getterInjector.injectMembers((GuiceTypedProvider<?>) renderer);
                            break ;
                        }
                    }
                    if (renderer == null) {
                    	assert this.viewRendererServiceProvider != null;
                        renderer = this.viewRendererServiceProvider.get().getRenderer(view);
                    }
                    this.rendererCache.put(view, renderer);
                }
            }
        }

        assert this.modelProvider != null;
        renderer.get().render(view, this.modelProvider.get().asMap());
    }

    @Override
    public T get() {
        return this.controller.get();
    }

    @Override
    public TypeLiteral<T> getTypeLiteral() {
        return this.controller.getTypeLiteral();
    }
}
