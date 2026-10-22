// "홈 화면에 추가" (scenario B): the icon should open the page it was added from, not the site root.
// The static manifest starts at /, so a page a passenger keeps swaps in one that starts at its own address.
import { useEffect } from 'react';

const sizes = ['192', '512'];

export function useHomeScreenStart(path: string | null, title?: string) {
  useEffect(() => {
    const link = document.querySelector<HTMLLinkElement>('link[rel="manifest"]');
    if (!path || !link) return;
    const origin = window.location.origin;
    const manifest = {
      name: title ? `${title} | 페어풀` : '페어풀(FairPool)',
      short_name: title ?? '페어풀',
      description: '공정한 카풀 정산',
      lang: 'ko',
      start_url: origin + path,
      scope: `${origin}/`,
      display: 'standalone',
      background_color: '#f2f4f7',
      theme_color: '#ffffff',
      icons: sizes.map((s) => ({ src: `${origin}/icons/icon-${s}.png`, sizes: `${s}x${s}`, type: 'image/png', purpose: 'any maskable' })),
    };
    const previous = link.getAttribute('href');
    link.setAttribute('href', `data:application/manifest+json,${encodeURIComponent(JSON.stringify(manifest))}`);
    return () => {
      if (previous) link.setAttribute('href', previous);
    };
  }, [path, title]);
}
