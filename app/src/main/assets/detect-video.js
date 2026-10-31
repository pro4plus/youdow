(function () {
  // Read-only discovery; no native JavaScript bridge, cookies or request interception.
  const page = location.href;
  const safe = (value, base) => {
    try {
      const u = new URL(value, base || page);
      return u.protocol === 'https:' && !u.username && !u.password ? u.href : '';
    } catch (_) { return ''; }
  };
  const platform = /(^|\.)(youtube\.com|youtu\.be|tiktok\.com|instagram\.com|facebook\.com|fb\.watch|snapchat\.com|vimeo\.com)$/i.test(location.hostname);
  const candidates = [];
  let protectedVideo = false;
  const permalink = video => {
    let node = video;
    for (let level = 0; node && level < 5; level++, node = node.parentElement) {
      for (const a of Array.from(node.querySelectorAll('a[href]')).slice(0, 30)) {
        const link = safe(a.href);
        if (/\/(?:watch\?v=|shorts\/|reels?\/|p\/|[^/]+\/video\/|videos\/)/i.test(link)) return link;
      }
    }
    return '';
  };
  const scan = (doc, base, depth) => {
    if (depth > 2) return;
    for (const video of Array.from(doc.querySelectorAll('video')).slice(0, 30)) {
      const rect = video.getBoundingClientRect();
      const style = doc.defaultView.getComputedStyle(video);
      if (rect.width < 32 || rect.height < 32 || rect.bottom <= 0 || rect.top >= doc.defaultView.innerHeight ||
          style.display === 'none' || style.visibility === 'hidden') continue;
      if (video.mediaKeys) { protectedVideo = true; continue; }
      const src = video.currentSrc || video.src || (video.querySelector('source[src]') || {}).src || '';
      if (!src && video.readyState === 0) continue;
      const direct = safe(src, base);
      // Blob/MSE URLs cannot be passed to the downloader. Use the source page instead.
      const detailPage = /\/(?:watch\?v=|shorts\/|reels?\/|p\/|[^/]+\/video\/|videos\/)/i.test(page);
      const target = platform ? ((detailPage ? safe(page) : '') || permalink(video) || direct || safe(page)) : (direct || safe(base));
      if (target) candidates.push({ target, title: (video.getAttribute('aria-label') || video.title || doc.title || 'فيديو').slice(0, 160),
        thumbnail: safe(video.poster, base), score: (video.paused ? 0 : 1e9) + rect.width * rect.height });
    }
    for (const frame of Array.from(doc.querySelectorAll('iframe')).slice(0, 20)) {
      const rect = frame.getBoundingClientRect();
      if (rect.width < 100 || rect.height < 60 || rect.bottom <= 0 || rect.top >= doc.defaultView.innerHeight) continue;
      try {
        if (frame.contentDocument) scan(frame.contentDocument, frame.contentWindow.location.href, depth + 1);
      } catch (_) { /* Cross-origin frames stay isolated. */ }
      const src = safe(frame.src, base);
      // Only recognized video embeds, not arbitrary iframe pages or ads.
      if (/^https:\/\/(?:www\.)?(?:youtube(?:-nocookie)?\.com\/embed\/[\w-]+|player\.vimeo\.com\/video\/\d+)/i.test(src)) {
        candidates.push({ target: src, title: (frame.title || 'فيديو مضمّن').slice(0,160), thumbnail: '', score: rect.width * rect.height });
      }
    }
  };
  scan(document, page, 0);
  candidates.sort((a, b) => b.score - a.score);
  const unique = candidates.filter((v,i,a) => a.findIndex(x => x.target === v.target) === i).slice(0,20);
  return JSON.stringify({ page, videos: unique, drm: protectedVideo });
})();
