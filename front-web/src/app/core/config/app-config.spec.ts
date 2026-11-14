import { loadAppConfig, parseAppConfig } from './app-config';

const valid = {
  apiUrl: 'https://api.ebank.example/api/v1/',
  wsUrl: 'wss://api.ebank.example/ws',
  keycloak: { url: 'https://auth.ebank.example/', realm: 'ebank', clientId: 'ebank-web' },
};

describe('parseAppConfig', () => {
  it('accepts a complete config and drops trailing slashes', () => {
    expect(parseAppConfig(valid)).toEqual({
      apiUrl: 'https://api.ebank.example/api/v1',
      wsUrl: 'wss://api.ebank.example/ws',
      keycloak: { url: 'https://auth.ebank.example', realm: 'ebank', clientId: 'ebank-web' },
    });
  });

  it('names every missing field', () => {
    expect(() => parseAppConfig({ apiUrl: 'https://x', keycloak: { realm: '' } })).toThrow(
      'missing wsUrl, keycloak.url, keycloak.realm, keycloak.clientId',
    );
  });

  it('rejects a value that is not a URL', () => {
    expect(() => parseAppConfig({ ...valid, apiUrl: 'not a url' })).toThrow('apiUrl is not a URL');
  });

  it('rejects nothing at all', () => {
    expect(() => parseAppConfig(null)).toThrow('missing apiUrl');
  });
});

describe('loadAppConfig', () => {
  it('reads /config.json without the HTTP cache', async () => {
    const fetcher = vi.fn().mockResolvedValue(new Response(JSON.stringify(valid)));
    await expect(loadAppConfig(fetcher)).resolves.toMatchObject({ wsUrl: valid.wsUrl });
    expect(fetcher).toHaveBeenCalledWith('/config.json', { cache: 'no-store' });
  });

  it('fails clearly when the file is not served', async () => {
    const fetcher = vi.fn().mockResolvedValue(new Response('', { status: 404 }));
    await expect(loadAppConfig(fetcher)).rejects.toThrow('/config.json answered 404');
  });
});
