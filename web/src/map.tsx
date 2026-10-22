// Course map from the Figma frames: MapLibre with OpenFreeMap tiles (OpenStreetMap data).
import { useEffect, useRef } from 'react';
import 'maplibre-gl/dist/maplibre-gl.css';
import pinPickup from './assets/figma/pin-pickup.svg';
import pinDropoff from './assets/figma/pin-dropoff.svg';
import carLocation from './assets/figma/car-location.svg';
import carDot from './assets/figma/car-dot.svg';

type LatLng = { lat: number; lng: number };

export function hasPoint(p: { lat?: number | null; lng?: number | null } | null | undefined): p is LatLng {
  return !!p && typeof p.lat === 'number' && typeof p.lng === 'number';
}

// Demo routing from the public OSRM server; the real app uses the NaviHelper or Kakao Mobility route.
// Falls back to straight segments when the request fails.
async function roadPath(stops: LatLng[]): Promise<[number, number][]> {
  const straight = stops.map((s): [number, number] => [s.lng, s.lat]);
  try {
    const coords = straight.map(([lng, lat]) => `${lng},${lat}`).join(';');
    const res = await fetch(`https://router.project-osrm.org/route/v1/driving/${coords}?overview=full&geometries=geojson`);
    const body = await res.json();
    return body.routes?.[0]?.geometry?.coordinates ?? straight;
  } catch {
    return straight;
  }
}

export function CourseMap({
  height,
  pickup,
  dropoff,
  car,
  carLabel,
}: {
  height: number;
  pickup?: LatLng | null;
  dropoff?: LatLng | null;
  car?: LatLng | null;
  carLabel?: string;
}) {
  const box = useRef<HTMLDivElement>(null);
  // the page re-fetches every few seconds; redraw only when a point actually moves
  const key = JSON.stringify([pickup, dropoff, car]);

  useEffect(() => {
    const [p, d, c] = JSON.parse(key) as (LatLng | null | undefined)[];
    let disposed = false;
    let map: import('maplibre-gl').Map | null = null;
    import('maplibre-gl').then(({ default: maplibregl }) => {
      if (disposed || !box.current) return;
      map = new maplibregl.Map({
        container: box.current,
        style: 'https://tiles.openfreemap.org/styles/positron',
        attributionControl: false,
        interactive: false,
      });
      const marker = (src: string, size: number, at: LatLng) => {
        const img = document.createElement('img');
        img.src = src;
        img.width = size;
        img.height = size;
        new maplibregl.Marker({ element: img }).setLngLat([at.lng, at.lat]).addTo(map!);
      };
      const points = [p, d, c].filter(hasPoint);
      if (hasPoint(p)) marker(pinPickup, 24, p);
      if (hasPoint(d)) marker(pinDropoff, 24, d);
      if (hasPoint(c)) marker(carLocation, 22, c);
      if (points.length === 1) map.jumpTo({ center: [points[0].lng, points[0].lat], zoom: 14 });
      else if (points.length > 1) {
        const bounds = new maplibregl.LngLatBounds();
        points.forEach((pt) => bounds.extend([pt.lng, pt.lat]));
        map.fitBounds(bounds, { padding: { top: 52, bottom: 32, left: 32, right: 32 }, maxZoom: 15, duration: 0 });
      }
      // road path in driving order: car → pickup → drop-off
      const stops = [c, p, d].filter(hasPoint);
      if (stops.length > 1) {
        map.on('load', async () => {
          const line = await roadPath(stops);
          if (disposed || !map) return;
          map.addSource('route', { type: 'geojson', data: { type: 'Feature', properties: {}, geometry: { type: 'LineString', coordinates: line } } });
          map.addLayer({
            id: 'route',
            type: 'line',
            source: 'route',
            layout: { 'line-join': 'round', 'line-cap': 'round' },
            paint: { 'line-color': '#2f6bff', 'line-width': 4 },
          });
        });
      }
    });
    return () => {
      disposed = true;
      map?.remove();
    };
  }, [key]);

  return (
    <div className="relative w-full overflow-hidden rounded-2xl bg-tile" style={{ height }}>
      {/* inline style: maplibre-gl.css sets position: relative on the map element */}
      <div ref={box} style={{ position: 'absolute', inset: 0 }} />
      {carLabel && (
        <div className="absolute top-3 left-3 flex items-center gap-1.5 rounded-full bg-card px-3 py-1.5 shadow-card">
          <img src={carDot} width={10} height={10} alt="" />
          <span className="t-caption">{carLabel}</span>
        </div>
      )}
      <span className="t-caption absolute right-2 bottom-1 text-sub opacity-80">© OpenStreetMap | OpenFreeMap</span>
    </div>
  );
}
