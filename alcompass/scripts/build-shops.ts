// Builds public/shops-in.json: every alcohol shop OpenStreetMap has in India, for the app to filter
// locally. Run weekly by .github/workflows/alcompass-shops.yml, or by hand:
//   npx tsx scripts/build-shops.ts                 # query Overpass (takes a few minutes)
//   npx tsx scripts/build-shops.ts --from raw.json # use a saved Overpass response
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { encodeDataset } from '../src/dataset';
import { buildCountryQuery, elementsToShops, OVERPASS_URLS, query, USER_AGENT, type Element } from '../src/osm';

const COUNTRY = 'IN';
const out = join(dirname(fileURLToPath(import.meta.url)), '..', 'public', 'shops-in.json');

async function main() {
  const fromIdx = process.argv.indexOf('--from');
  let elements: Element[];
  if (fromIdx > 0) {
    elements = (JSON.parse(readFileSync(process.argv[fromIdx + 1], 'utf8')) as { elements: Element[] }).elements;
  } else {
    elements = await query(OVERPASS_URLS[0], buildCountryQuery(COUNTRY), {
      fetchImpl: fetch,
      userAgent: USER_AGENT,
      timeoutMs: 15 * 60_000,
    });
  }
  const shops = elementsToShops(elements, null);
  // An empty or tiny result means Overpass failed quietly: keep the old file rather than ship nothing.
  if (shops.length < 500) throw new Error(`only ${shops.length} shops; refusing to overwrite the dataset`);
  const dataset = encodeDataset(COUNTRY, shops);
  mkdirSync(dirname(out), { recursive: true });
  writeFileSync(out, JSON.stringify(dataset));
  console.log(`${shops.length} shops, ${Math.round(JSON.stringify(dataset).length / 1024)} KB -> ${out}`);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
