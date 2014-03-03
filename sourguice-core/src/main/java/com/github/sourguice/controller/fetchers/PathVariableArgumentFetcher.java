package com.github.sourguice.controller.fetchers;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Map;

import javax.annotation.CheckForNull;
import javax.servlet.http.HttpServletRequest;

import com.github.sourguice.annotation.request.PathVariable;
import com.github.sourguice.annotation.request.PathVariablesMap;
import com.github.sourguice.throwable.invocation.NoSuchPathVariableException;
import com.github.sourguice.throwable.invocation.NoSuchRequestParameterException;
import com.google.inject.Injector;
import com.google.inject.TypeLiteral;

/**
 * Fetcher that handles @{@link PathVariable} annotated arguments
 *
 * @param <T> The type of the argument to fetch
 *
 * @author Salomon BRYS <salomon.brys@gmail.com>
 */
public class PathVariableArgumentFetcher<T> extends ArgumentFetcher<T> {

	/**
	 * The annotations containing needed informations to fetch the argument
	 */
	private final PathVariable infos;

	/**
	 * @see ArgumentFetcher#ArgumentFetcher(Type, int, Annotation[])
	 * @param type The type of the argument to fetch
	 * @param pos The position of the method's argument to fetch
	 * @param annotations Annotations that were found on the method's argument
	 * @param infos The annotations containing needed informations to fetch the argument
	 * @param ref The reference map that links path variable name to their index when a url matches
	 * @param check Whether or not to check that ref contains the reference to the path variable
	 */
	public PathVariableArgumentFetcher(final TypeLiteral<T> type, final Annotation[] annotations, final PathVariable infos, final Map<String, Integer> ref, final boolean check) {
		super(type, annotations);
		this.infos = infos;
		if (check && !ref.containsKey(infos.value())) {
			throw new NoSuchPathVariableException(infos.value());
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	protected @CheckForNull T getPrepared(final HttpServletRequest req, final @PathVariablesMap Map<String, String> pathVariables, final Injector injector) throws NoSuchRequestParameterException {
		if (pathVariables == null || pathVariables.get(this.infos.value()) == null) {
			// This should never happen (I can't see a way to test it) since
			//   1- Existence of the pathvariable key has been checked in constructor
			//   2- If we are here, it means that the URL has matched the regex with the corresponding key
			throw new NoSuchRequestParameterException(this.infos.value(), "path variables");
		}
		return convert(injector, pathVariables.get(this.infos.value()));
	}
}
