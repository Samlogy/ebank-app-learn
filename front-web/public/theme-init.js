// Runs before first paint so a saved theme never flashes the device theme.
// Kept outside index.html so the Content-Security-Policy can forbid inline scripts.
try {
  var theme = localStorage.getItem('ebank.theme');
  if (theme === 'light' || theme === 'dark') {
    document.documentElement.setAttribute('data-theme', theme);
  }
} catch (e) {}
