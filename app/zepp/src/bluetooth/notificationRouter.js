// The @zos/ble API accepts only one mstOnCharaNotification callback per process.
// Each module owns a set of characteristic UUIDs and registers one route here.
// The router sends each notification to the first route that matches its UUID.

import * as ble from '@zos/ble';
import { eventCharacteristicUuid, uuidMatches } from './gattMatch';

const routes = [];

function routeMatches(route, receivedUuid) {
  for (let i = 0; i < route.uuids.length; i++) {
    if (uuidMatches(receivedUuid, route.uuids[i])) return true;
  }
  return false;
}

if (typeof ble.mstOnCharaNotification === 'function') {
  ble.mstOnCharaNotification((res) => {
    const receivedUuid = eventCharacteristicUuid(res);
    if (!receivedUuid) return;
    // Use a snapshot of the route list so a remove call does not change the loop order.
    const snapshot = routes.slice();
    for (let i = 0; i < snapshot.length; i++) {
      const route = snapshot[i];
      if (routeMatches(route, receivedUuid)) {
        try {
          route.handler(res);
        } catch (err) {
          console.log('Notification route handler failed:', err);
        }
        return;
      }
    }
  });
}

export function addNotificationRoute(uuids, handler) {
  const route = { uuids: (uuids || []).slice(), handler };
  routes.push(route);
  let removed = false;
  return () => {
    if (removed) return;
    removed = true;
    const index = routes.indexOf(route);
    if (index !== -1) routes.splice(index, 1);
  };
}
