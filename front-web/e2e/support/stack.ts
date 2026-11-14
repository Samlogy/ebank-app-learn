/**
 * Helpers that talk to the local stack directly (no browser): the Keycloak Admin API, tokens from
 * the dev-only "ebank-cli" client, and the EBANK API. Dev only: never point them at qua or prod.
 */
import { randomUUID } from 'node:crypto';

export const API = process.env['E2E_API_URL'] ?? 'http://localhost:8080';
export const KC = process.env['E2E_KEYCLOAK_URL'] ?? 'http://localhost:8180';
const KC_ADMIN = process.env['KEYCLOAK_ADMIN_USERNAME'] ?? 'admin';
const KC_ADMIN_PASSWORD = process.env['KEYCLOAK_ADMIN_PASSWORD'] ?? 'admin';
/** Meets the realm's password policy (12+ characters). */
export const PASSWORD = 'Test-1234!Ebank';

export interface TestUser {
  email: string;
  id: string;
}

async function json<T>(response: Response, what: string): Promise<T> {
  if (!response.ok) {
    throw new Error(`${what}: HTTP ${response.status} ${await response.text()}`);
  }
  const text = await response.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

async function adminToken(): Promise<string> {
  const response = await fetch(`${KC}/realms/master/protocol/openid-connect/token`, {
    method: 'POST',
    body: new URLSearchParams({
      grant_type: 'password',
      client_id: 'admin-cli',
      username: KC_ADMIN,
      password: KC_ADMIN_PASSWORD,
    }),
  });
  return (await json<{ access_token: string }>(response, 'Keycloak admin login')).access_token;
}

async function kc<T = unknown>(method: string, path: string, body?: unknown): Promise<T> {
  const response = await fetch(`${KC}/admin/realms/ebank${path}`, {
    method,
    headers: { Authorization: `Bearer ${await adminToken()}`, 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  return json<T>(response, `Keycloak ${method} ${path}`);
}

/** The dev-only public client with the password grant, so tests can get tokens without a browser. */
export async function ensureCliClient(): Promise<void> {
  const existing = await kc<unknown[]>('GET', '/clients?clientId=ebank-cli');
  if (existing.length) {
    return;
  }
  await kc('POST', '/clients', {
    clientId: 'ebank-cli',
    name: 'Local testing only',
    enabled: true,
    publicClient: true,
    standardFlowEnabled: false,
    directAccessGrantsEnabled: true,
    protocolMappers: [
      {
        name: 'audience-ebank-api',
        protocol: 'openid-connect',
        protocolMapper: 'oidc-audience-mapper',
        config: {
          'included.client.audience': 'ebank-api',
          'access.token.claim': 'true',
          'id.token.claim': 'false',
        },
      },
    ],
  });
}

/** A new verified user, with a realm role on top of CUSTOMER when given. */
export async function createUser(prefix: string, role?: 'ADMIN' | 'ADVISOR'): Promise<TestUser> {
  const email = `${prefix}.${Date.now()}.${randomUUID().slice(0, 6)}@ebank.local`;
  await kc('POST', '/users', {
    username: email,
    email,
    emailVerified: true,
    enabled: true,
    firstName: 'E2E',
    lastName: prefix,
    requiredActions: [],
    credentials: [{ type: 'password', value: PASSWORD, temporary: false }],
  });
  const [user] = await kc<{ id: string }[]>(
    'GET',
    `/users?username=${encodeURIComponent(email)}&exact=true`,
  );
  if (role) {
    const representation = await kc('GET', `/roles/${role}`);
    await kc('POST', `/users/${user.id}/role-mappings/realm`, [representation]);
  }
  return { email, id: user.id };
}

/** An access token through the password grant. Skips the browser flow, TOTP included. */
export async function tokenFor(user: TestUser): Promise<string> {
  const response = await fetch(`${KC}/realms/ebank/protocol/openid-connect/token`, {
    method: 'POST',
    body: new URLSearchParams({
      grant_type: 'password',
      client_id: 'ebank-cli',
      username: user.email,
      password: PASSWORD,
      scope: 'openid',
    }),
  });
  return (await json<{ access_token: string }>(response, `Token for ${user.email}`)).access_token;
}

export async function api<T = unknown>(
  token: string,
  method: string,
  path: string,
  body?: unknown,
): Promise<T> {
  const headers: Record<string, string> = {
    Authorization: `Bearer ${token}`,
    'Content-Type': 'application/json',
  };
  if (method === 'POST') {
    headers['Idempotency-Key'] = randomUUID();
  }
  const response = await fetch(`${API}/api/v1${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  return json<T>(response, `${method} ${path}`);
}

/** An active EUR account of {@link owner}, activated by {@link admin}. */
export async function activeAccount(owner: TestUser, admin: TestUser): Promise<string> {
  const { accountId } = await api<{ accountId: string }>(
    await tokenFor(owner),
    'POST',
    '/accounts',
    {
      type: 'CURRENT',
      currency: 'EUR',
    },
  );
  await api(await tokenFor(admin), 'PATCH', `/accounts/${accountId}/activate`);
  return accountId;
}

export async function deposit(admin: TestUser, accountId: string, amount: number): Promise<void> {
  await api(await tokenFor(admin), 'POST', '/transactions/deposits', {
    receiverAccountId: accountId,
    amount,
    currency: 'EUR',
    reference: 'e2e deposit',
  });
}

export async function transfer(
  from: TestUser,
  sender: string,
  receiver: string,
  amount: number,
): Promise<void> {
  await api(await tokenFor(from), 'POST', '/transactions', {
    senderAccountId: sender,
    receiverAccountId: receiver,
    amount,
    currency: 'EUR',
    reference: 'e2e transfer',
  });
}
