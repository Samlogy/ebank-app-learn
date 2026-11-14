import { bootstrapApplication } from '@angular/platform-browser';

import { App } from './app/app';
import { appConfig } from './app/app.config';
import { APP_CONFIG, loadAppConfig } from './app/core/config/app-config';

// The runtime config is loaded before Angular starts: the Keycloak client and every API service
// need it at construction time.
loadAppConfig()
  .then((config) =>
    bootstrapApplication(App, {
      ...appConfig,
      providers: [{ provide: APP_CONFIG, useValue: config }, ...appConfig.providers],
    }),
  )
  .catch((error: unknown) => {
    console.error(error);
    showStartupError();
  });

/** Plain DOM: Angular is not running, so this is all the user would otherwise get. */
function showStartupError(): void {
  const root = document.querySelector('app-root');
  if (!root) {
    return;
  }
  const box = document.createElement('div');
  box.className = 'startup-error';
  box.setAttribute('role', 'alert');
  const title = document.createElement('h1');
  title.textContent = 'EBANK could not start';
  const text = document.createElement('p');
  text.textContent = 'The application settings could not be loaded. Please try again in a moment.';
  box.append(title, text);
  root.replaceChildren(box);
}
