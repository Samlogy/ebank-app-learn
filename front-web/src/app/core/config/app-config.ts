import { InjectionToken } from '@angular/core';

/**
 * Settings that change per environment. They are read from /config.json when the app starts, so
 * the same image runs in dev, qua and prod. Everything here reaches the browser: never a secret.
 */
export interface AppConfig {
  /** REST API base, without a trailing slash, e.g. https://api.ebank.example/api/v1. */
  apiUrl: string;
  /** STOMP endpoint, e.g. wss://api.ebank.example/ws. */
  wsUrl: string;
  keycloak: {
    url: string;
    realm: string;
    clientId: string;
  };
}

export const APP_CONFIG = new InjectionToken<AppConfig>('APP_CONFIG');

export const CONFIG_URL = '/config.json';

/** Loads and checks the runtime configuration. Rejects with a readable message when it is unusable. */
export async function loadAppConfig(fetcher: typeof fetch = fetch): Promise<AppConfig> {
  const response = await fetcher(CONFIG_URL, { cache: 'no-store' });
  if (!response.ok) {
    throw new Error(`${CONFIG_URL} answered ${response.status}`);
  }
  return parseAppConfig(await response.json());
}

export function parseAppConfig(value: unknown): AppConfig {
  const config = value as Partial<AppConfig> | null;
  const keycloak = config?.keycloak;
  const fields: [string, unknown][] = [
    ['apiUrl', config?.apiUrl],
    ['wsUrl', config?.wsUrl],
    ['keycloak.url', keycloak?.url],
    ['keycloak.realm', keycloak?.realm],
    ['keycloak.clientId', keycloak?.clientId],
  ];
  const missing = fields.filter(([, field]) => typeof field !== 'string' || field.trim() === '');
  if (missing.length) {
    throw new Error(`${CONFIG_URL} is missing ${missing.map(([name]) => name).join(', ')}`);
  }
  for (const [name, url] of [
    ['apiUrl', config!.apiUrl],
    ['wsUrl', config!.wsUrl],
    ['keycloak.url', keycloak!.url],
  ] as const) {
    if (!URL.canParse(url!)) {
      throw new Error(`${CONFIG_URL}: ${name} is not a URL`);
    }
  }
  const trim = (url: string) => url.replace(/\/+$/, '');
  return {
    apiUrl: trim(config!.apiUrl!),
    wsUrl: trim(config!.wsUrl!),
    keycloak: { url: trim(keycloak!.url!), realm: keycloak!.realm!, clientId: keycloak!.clientId! },
  };
}
