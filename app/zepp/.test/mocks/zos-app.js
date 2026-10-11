// Stand-in for @zos/app: the two permission functions of API_LEVEL 3.0.
//
// The documented result codes:
// - queryPermission returns one number per permission: 0 not authorized,
//   1 unknown permission, 2 authorized.
// - requestPermission returns 0 when the watch asks the user (the callback
//   gets the result array), 1 when it can make no request, and 2 when the
//   permission is already authorized.
//
// By default every permission is authorized, so a test that does not care
// about permissions sees the same app as before this mock existed.

const AUTHORIZED = 2;

let status = {};
let requestOutcome = { returns: 0, grants: true, callsBack: true };
const requests = [];

export function queryPermission(option) {
  const permissions = (option && option.permissions) || [];
  return permissions.map(p => (p in status ? status[p] : AUTHORIZED));
}

export function requestPermission(option) {
  const permissions = (option && option.permissions) || [];
  requests.push([...permissions]);
  const { returns, grants, callsBack } = requestOutcome;
  // Code 2 means the permission is authorized now.
  if (returns === AUTHORIZED) permissions.forEach(p => { status[p] = AUTHORIZED; });
  if (callsBack) {
    // The user answers the dialog later: the status changes just before the callback.
    setTimeout(() => {
      if (grants) permissions.forEach(p => { status[p] = AUTHORIZED; });
      option.callback(permissions.map(() => (grants ? AUTHORIZED : 0)));
    }, 0);
  }
  return returns;
}

export const appRecorder = {
  reset() {
    status = {};
    requestOutcome = { returns: 0, grants: true, callsBack: true };
    requests.length = 0;
  },

  /** Sets what queryPermission reports for [permission]: 0, 1 or 2. */
  setPermission(permission, value) {
    status[permission] = value;
  },

  /**
   * Sets how requestPermission behaves: [returns] is its return code, and
   * [grants] is whether the user allows the request in the callback.
   * [callsBack] is whether the callback runs; by default only for code 0. A
   * true value with another code makes a watch that returns a code and also
   * calls back.
   */
  setRequestOutcome(returns, grants = true, callsBack = returns === 0) {
    requestOutcome = { returns, grants, callsBack };
  },

  requests() {
    return requests.map(r => [...r]);
  }
};
