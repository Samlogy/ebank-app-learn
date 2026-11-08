import { DOCUMENT, Injectable, inject, signal } from '@angular/core';

export type ThemePreference = 'system' | 'light' | 'dark';

const STORAGE_KEY = 'ebank.theme';

/**
 * "system" follows the device: styles.scss uses light-dark(), driven by color-scheme.
 * The inline script in index.html applies the saved choice before first paint.
 */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  private readonly root = inject(DOCUMENT).documentElement;
  readonly preference = signal<ThemePreference>(readSaved());

  constructor() {
    this.apply(this.preference());
  }

  set(preference: ThemePreference): void {
    this.preference.set(preference);
    this.apply(preference);
    try {
      localStorage.setItem(STORAGE_KEY, preference);
    } catch {
      // Storage can be blocked. The choice then lasts until the next reload.
    }
  }

  private apply(preference: ThemePreference): void {
    if (preference === 'system') {
      this.root.removeAttribute('data-theme');
    } else {
      this.root.setAttribute('data-theme', preference);
    }
  }
}

function readSaved(): ThemePreference {
  try {
    const saved = localStorage.getItem(STORAGE_KEY);
    return saved === 'light' || saved === 'dark' ? saved : 'system';
  } catch {
    return 'system';
  }
}
