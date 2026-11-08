package com.ebank.auth.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param publicPaths paths that need no token
 * @param corsAllowedOrigins browser origins allowed to call the API
 */
@ConfigurationProperties("ebank.security")
record SecurityProperties(
        @DefaultValue List<String> publicPaths, @DefaultValue List<String> corsAllowedOrigins) {}
