import { TestBed } from '@angular/core/testing';
import { afterEach, vi } from 'vitest';

import { ThemeService } from './theme.service';

describe('ThemeService', () => {
  const root = document.documentElement;

  beforeEach(() => {
    // Node 25 has its own localStorage global that hides the jsdom one.
    const saved = new Map<string, string>();
    vi.stubGlobal('localStorage', {
      getItem: (key: string) => saved.get(key) ?? null,
      setItem: (key: string, value: string) => saved.set(key, value),
    });
    root.removeAttribute('data-theme');
    TestBed.resetTestingModule();
  });

  afterEach(() => vi.unstubAllGlobals());

  it('follows the device by default', () => {
    const theme = TestBed.inject(ThemeService);
    expect(theme.preference()).toBe('system');
    expect(root.hasAttribute('data-theme')).toBe(false);
  });

  it('applies and saves an explicit choice', () => {
    TestBed.inject(ThemeService).set('dark');
    expect(root.getAttribute('data-theme')).toBe('dark');
    expect(localStorage.getItem('ebank.theme')).toBe('dark');
  });

  it('restores a saved choice', () => {
    localStorage.setItem('ebank.theme', 'light');
    expect(TestBed.inject(ThemeService).preference()).toBe('light');
    expect(root.getAttribute('data-theme')).toBe('light');
  });

  it('goes back to the device setting', () => {
    const theme = TestBed.inject(ThemeService);
    theme.set('dark');
    theme.set('system');
    expect(root.hasAttribute('data-theme')).toBe(false);
  });

  it('ignores an unknown saved value', () => {
    localStorage.setItem('ebank.theme', 'neon');
    expect(TestBed.inject(ThemeService).preference()).toBe('system');
  });
});
