// A file, not an inline script, so the Content-Security-Policy can forbid inline scripts.
parent.postMessage(location.href, location.origin);
