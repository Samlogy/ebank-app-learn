import { provideHttpClient, withInterceptors } from '@angular/common/http';
import {
  ApplicationConfig,
  inject,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
} from '@angular/core';
import { provideRouter, TitleStrategy } from '@angular/router';

import { routes } from './app.routes';
import { authInterceptor } from './core/auth/auth.interceptor';
import { AuthService } from './core/auth/auth.service';
import { EbankTitleStrategy } from './core/layout/title-strategy';
import { ChatTransport } from './features/chatbot/chat-transport';
import { DemoChatTransport } from './features/chatbot/demo-chat-transport';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes),
    provideHttpClient(withInterceptors([authInterceptor])),
    provideAppInitializer(() => inject(AuthService).init()),
    { provide: TitleStrategy, useClass: EbankTitleStrategy },
    // Swap this for a WebSocket implementation once the backend chatbot module exists.
    { provide: ChatTransport, useClass: DemoChatTransport },
  ],
};
