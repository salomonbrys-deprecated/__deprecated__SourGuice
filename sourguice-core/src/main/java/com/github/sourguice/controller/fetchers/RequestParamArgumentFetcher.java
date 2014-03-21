package com.github.sourguice.controller.fetchers;

import java.util.Collection;
import java.util.Map;

import javax.annotation.CheckForNull;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.servlet.http.HttpServletRequest;

import com.github.sourguice.annotation.request.RequestParam;
import com.github.sourguice.conversion.ConversionService;
import com.github.sourguice.throwable.invocation.NoSuchRequestParameterException;
import com.github.sourguice.value.ValueConstants;
import com.google.inject.TypeLiteral;

/**
 * Fetcher that handles @{@link RequestParam} annotated arguments
 *
 * @param <T> The type of the argument to fetch
 *
 * @author Salomon BRYS <salomon.brys@gmail.com>
 */
public class RequestParamArgumentFetcher<T> extends AbstractArgumentFetcher<T> {

	/**
	 * The annotations containing needed informations to fetch the argument
	 */
	private final RequestParam infos;

	/**
	 * If this fetcher is supposed to fetch a collection or a map, then it's a specialized delegate that will handle it
	 */
	private @CheckForNull Delegate<T> delegate = null;

	/**
	 * Provider for the current HTTP request
	 */
	@Inject
	private @CheckForNull Provider<HttpServletRequest> requestProvider;

	/**
	 * A delegate of {@link RequestParamArgumentFetcher}
	 *
	 * @param <T> The type of the argument to fetch
	 */
	public static interface Delegate<T> {
		/**
		 * This is where subclass fetch the argument
		 *
		 * @param req The HTTPRequest to get the variable from
		 * @param conversionService The conversion service to use
		 * @return The argument to be passed to the invocation
		 * @throws NoSuchRequestParameterException In case of a parameter asked from request argument or path variable that does not exists
		 */
		public abstract T getPrepared(HttpServletRequest req, ConversionService conversionService) throws NoSuchRequestParameterException;
	}

	/**
	 * @see AbstractArgumentFetcher#AbstractArgumentFetcher(TypeLiteral)
	 *
	 * @param type The type of the argument to fetch
	 * @param infos The annotations containing needed informations to fetch the argument
	 */
	public RequestParamArgumentFetcher(final TypeLiteral<T> type, final RequestParam infos) {
		super(type);
		this.infos = infos;

		final Class<? super T> rawType = type.getRawType();
		if (Collection.class.isAssignableFrom(rawType)) {
			this.delegate = new RequestParamCollectionArgumentFetcher<>(type, infos);
		}
		else if (Map.class.isAssignableFrom(rawType)) {
			this.delegate = new RequestParamMapArgumentFetcher<>(type, infos);
		}
	}

	@Override
	public @CheckForNull T getPrepared() throws NoSuchRequestParameterException {
		assert this.requestProvider != null;
		final HttpServletRequest req = this.requestProvider.get();

		// If there is a specialized delegate, let it handle the fetch
		if (this.delegate != null) {
			assert this.conversionServiceProvider != null;
			return this.delegate.getPrepared(req, this.conversionServiceProvider.get());
		}

		// If the parameter does not exists, returns the default value or, if there are none, throw an exception
		if (req.getParameter(this.infos.value()) == null) {
			if (!this.infos.defaultValue().equals(ValueConstants.DEFAULT_NONE)) {
				return convert(this.infos.defaultValue());
			}
			throw new NoSuchRequestParameterException(this.infos.value(), "request parameters");
		}
		// Returns the converted parameter value
		if (req.getParameterValues(this.infos.value()).length == 1) {
			return convert(req.getParameter(this.infos.value()));
		}
		return convert(req.getParameterValues(this.infos.value()));
	}
}
