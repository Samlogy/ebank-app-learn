import { API, ensureCliClient, KC } from './stack';

/** Fails fast with a clear message when the stack is not up, then prepares Keycloak. */
export default async function globalSetup(): Promise<void> {
  for (const [name, url] of [
    ['Backend', `${API}/actuator/health/readiness`],
    ['Keycloak', `${KC}/realms/ebank`],
  ]) {
    const ok = await fetch(url).then(
      (r) => r.ok,
      () => false,
    );
    if (!ok) {
      throw new Error(
        `${name} is not reachable at ${url}. Start the local stack first (see playwright.config.ts).`,
      );
    }
  }
  await ensureCliClient();
}
