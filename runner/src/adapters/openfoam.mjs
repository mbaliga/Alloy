// OpenFOAM (flow: simpleFoam, buoyantSimpleFoam, chtMultiRegionFoam) is part of the Runner's plan (docs/EXTENSIONS_SPEC.md, A4 and A5) but
// its case generation arrives with the airflow step (A5), which owns the cases it must validate. This adapter only reports that it is
// not available, so /v1/health says so and a job asking for it is refused with that reason.
export const openfoam = {
  id: 'openfoam', label: 'OpenFOAM (flow)', kinds: [], real: true,
  async available() { return { ok: false, reason: 'not implemented in this version of the runner (flow cases arrive with step A5)' }; },
  async run() { throw Object.assign(new Error('OpenFOAM is not implemented in this version of the runner'), { code: 'NO_SOLVER' }); },
};
