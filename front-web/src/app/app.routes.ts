import { Routes } from '@angular/router';

import { authGuard, guestGuard } from './core/auth/auth.guards';
import { Shell } from './core/layout/shell';

export const routes: Routes = [
  {
    path: 'auth',
    canActivate: [guestGuard],
    title: 'Sign in',
    loadComponent: () => import('./features/auth/auth-page').then((m) => m.AuthPage),
  },
  {
    path: '',
    component: Shell,
    canActivate: [authGuard],
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'account' },
      {
        path: 'account',
        title: 'Accounts',
        loadComponent: () =>
          import('./features/accounts/accounts-page').then((m) => m.AccountsPage),
      },
      {
        path: 'transactions',
        title: 'Transactions',
        loadComponent: () =>
          import('./features/transactions/transactions-page').then((m) => m.TransactionsPage),
      },
      {
        path: 'analytics',
        title: 'Analytics',
        loadComponent: () =>
          import('./features/analytics/analytics-page').then((m) => m.AnalyticsPage),
      },
      {
        path: 'chatbot',
        title: 'Chatbot',
        loadComponent: () => import('./features/chatbot/chatbot-page').then((m) => m.ChatbotPage),
      },
      {
        path: 'profile',
        title: 'Profile',
        loadComponent: () => import('./features/profile/profile-page').then((m) => m.ProfilePage),
      },
      {
        path: 'notifications',
        title: 'Notifications',
        loadComponent: () =>
          import('./features/notifications/notifications-page').then((m) => m.NotificationsPage),
      },
    ],
  },
  { path: '**', redirectTo: '' },
];
