package com.github.sourguice.controller.fetchers;

import java.util.Map;

import javax.annotation.CheckForNull;
import javax.inject.Inject;
import javax.inject.Provider;

import com.github.sourguice.annotation.request.PathVariable;
import com.github.sourguice.annotation.request.PathVariablesMap;
import com.github.sourguice.throwable.invocation.NoSuchPathVariableException;
import com.github.sourguice.throwable.invocation.NoSuchRequestParameterException;
import com.google.inject.TypeLiteral;

/**
 * Fetcher that handles @{@link PathVariable} annotated arguments
 *
 * @param <T> The type of the argument to fetch
 *
 * @author Salomon BRYS <salomon.brys@gmail.com>
 */
public class PathVariableArgumentFetcher<T> extends AbstractArgumentFetcher<T> {

	/**
	 * The annotations containing needed informations to fetch the argument
	 */
	private final PathVariable infos;

	/**
	 * The provider for the path variable map, from which the result will be found
	 */
	@Inject
	private @CheckForNull @PathVariablesMap Provider<Map<String, String>> pathVariablesProvider;

	/**
	 * @param type The type of the argument to fetch
	 * @param infos The annotations containing needed informations to fetch the argument
	 * @param ref The reference map that links path variable name to their index when a url matches
	 */
	public PathVariableArgumentFetcher(final TypeLiteral<T> type, final PathVariable infos, final @CheckForNull Map<String, Integer> ref) {
		super(type);
		this.infos = infos;
		if (ref != null && !ref.containsKey(infos.value())) {
			throw new NoSuchPathVariableException(infos.value());
		}
	}

	@Override
	public @CheckForNull T getPrepared() throws NoSuchRequestParameterException {
		assert this.pathVariablesProvider != null;
		final Map<String, String> pathVariables = this.pathVariablesProvider.get();
		if (pathVariables == null || pathVariables.get(this.infos.value()) == null) {
			// This should never happen (I can't see a way to test it) since
			//   1- Existence of the pathvariable key has been checked in constructor
			//   2- If we are here, it means that the URL has matched the regex with the corresponding key
			throw new NoSuchRequestParameterException(this.infos.value(), "path variables");
		}
		return convert(pathVariables.get(this.infos.value()));
	}
}
