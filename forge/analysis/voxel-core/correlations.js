// Heat-transfer correlations used by the voxel core's thermal boundary conditions (docs/EXTENSIONS_SPEC.md section 4, A2).
// These are COPIES of the pure functions in examples/ext/thermal-estimate/main.js (A1): the A2 package must not depend on the A1
// package, so the core owns its own copy. test_voxel_core.mjs compares both over a grid of inputs and fails if they drift apart.
// All SI; temperatures in kelvin; air near 1 atm. See the A1 source for the references (Churchill and Chu, Incropera, Cengel).
export const SIGMA = 5.670374419e-8;
export const GRAV = 9.80665;
const sq = (x) => x * x;

/** Air properties at film temperature T (K), 1 atm. Fits to tables for dry air, 250 to 450 K. */
export function airProps(T) {
  const rho = 101325 / (287.05 * T), mu = 1.846e-5 * Math.pow(T / 300, 0.7), k = 0.0260 * Math.pow(T / 300, 0.85);
  const cp = 1007, Pr = Math.min(0.74, Math.max(0.66, 0.71 - 0.00015 * (T - 300)));
  return { rho, mu, nu: mu / rho, k, cp, Pr, beta: 1 / T, alpha: k / (rho * cp) };
}
export const rayleigh = (dT, L, p) => GRAV * p.beta * Math.abs(dT) * L * L * L / (p.nu * p.alpha);
export const nuVertical = (Ra, Pr) => sq(0.825 + 0.387 * Math.pow(Ra, 1 / 6) / Math.pow(1 + Math.pow(0.492 / Pr, 9 / 16), 8 / 27));
export const nuHorizontalEnhanced = (Ra) => (Ra < 1e7 ? 0.54 * Math.pow(Ra, 0.25) : 0.15 * Math.pow(Ra, 1 / 3));
export const nuHorizontalRestricted = (Ra) => 0.27 * Math.pow(Ra, 0.25);
export const nuForcedPlate = (Re, Pr) => (Re < 5e5 ? 0.664 * Math.sqrt(Re) * Math.cbrt(Pr) : (0.037 * Math.pow(Re, 0.8) - 871) * Math.cbrt(Pr));
export const hRadiation = (eps, Ts, Ta) => eps * SIGMA * (sq(Ts) + sq(Ta)) * (Ts + Ta);

/**
 * Natural (and optionally forced) convection coefficient of one face.
 * orient: 'side' | 'up' | 'down' (the way the surface faces); hotter: the surface is hotter than the fluid; L: characteristic length (m).
 */
export function hFace({ orient, hotter, L, Ts, Tf, airspeed = 0 }) {
  const Tm = (Ts + Tf) / 2, p = airProps(Tm), dT = Math.max(Math.abs(Ts - Tf), 1e-3), Ra = rayleigh(dT, L, p);
  let Nu, outside = false;
  if (orient === 'side') { Nu = nuVertical(Ra, p.Pr); outside = Ra < 1e-1; }
  else {
    const enhanced = (orient === 'up') === hotter; // hot face up, or cold face down
    Nu = enhanced ? nuHorizontalEnhanced(Ra) : nuHorizontalRestricted(Ra);
    outside = enhanced ? (Ra < 1e4 || Ra > 1e11) : (Ra < 1e5 || Ra > 1e10);
  }
  Nu = Math.max(Nu, 1);
  if (airspeed > 0) {
    const Nf = Math.max(nuForcedPlate(airspeed * L / p.nu, p.Pr), 0);
    Nu = Math.cbrt(Nu * Nu * Nu + Nf * Nf * Nf);
  }
  return { h: Nu * p.k / L, Ra, Nu, outside, Tm };
}

const FACE_N = { '+x': [1, 0, 0], '-x': [-1, 0, 0], '+y': [0, 1, 0], '-y': [0, -1, 0], '+z': [0, 0, 1], '-z': [0, 0, -1] };
/** Orientation of a face for a gravity direction ("-z" means gravity points down the -z axis): 'up' | 'down' | 'side'. */
export function orientOf(face, gravity) {
  const n = FACE_N[face], g = FACE_N[gravity] || FACE_N['-z'], d = -(n[0] * g[0] + n[1] * g[1] + n[2] * g[2]);
  return d > 0.5 ? 'up' : d < -0.5 ? 'down' : 'side';
}
