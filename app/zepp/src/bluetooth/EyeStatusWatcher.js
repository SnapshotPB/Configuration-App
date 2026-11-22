// Owns the live eye subscription and state for one board connection.
// The board sends notifications at approximately 10 Hz only while the CCC is
// on. A 2 s delay after the last listener leaves prevents two CCC writes on a
// fast page close and open. If no sample arrives for 1 s, the state returns to
// Waiting so a screen does not show an old level as a live level.

import { decodeEyeStatus, EyeStatusUuids } from './eyeStatus';
import { addressMatches } from './gattMatch';
import { addNotificationRoute } from './notificationRouter';
import { isTransportTimeout } from './OtsClient';

// Mirrors EyeReadingState in core:model:
// - Waiting: the watcher is active, with no sample in the last second.
// - Reading: the last decoded eye sample from the board.
// - Unsupported: the board has no eye status characteristic.
// - Failed: the CCC enable write timed out.
// A null state means no listener is active or the connection is closed.
export const EyeReadingKind = {
  Waiting: 'Waiting',
  Reading: 'Reading',
  Unsupported: 'Unsupported',
  Failed: 'Failed'
};

export const EYE_STALE_MS = 1000;
export const EYE_UNWATCH_DELAY_MS = 2000;

export class EyeStatusWatcher {
  constructor({ supported = false, setNotifications, address } = {}) {
    this._supported = Boolean(supported);
    this._setNotifications = setNotifications;
    this._address = address || null;
    this._closed = false;
    this._state = null;
    this._listeners = [];
    this._notificationsOn = false;
    this._desiredOn = false;
    this._writeInFlight = false;
    this._enableRefused = false;
    this._staleTimer = null;
    this._unwatchTimer = null;
    this._decodeErrorLogged = false;
    this._removeRoute = null;

    if (this._address) {
      this._removeRoute = addNotificationRoute([EyeStatusUuids.Status], (res) => {
        if (this._closed) return;
        if (!addressMatches(res, this._address)) return;
        try {
          const reading = decodeEyeStatus(res && res.value);
          this.acceptReading(reading);
        } catch (err) {
          if (!this._decodeErrorLogged) {
            this._decodeErrorLogged = true;
            console.log('EyeStatus decode failed:', err);
          }
        }
      });
    }
  }

  get state() {
    return this._state;
  }

  get closed() {
    return this._closed;
  }

  watch(listener) {
    if (this._closed) {
      this._invokeListener(listener, null);
      return () => {};
    }

    const wasEmpty = this._listeners.length === 0;
    const hadUnwatchTimer = this._unwatchTimer !== null;
    if (hadUnwatchTimer) {
      this._clearUnwatchTimer();
    }

    const entry = { fn: listener };
    this._listeners.push(entry);

    if (wasEmpty) {
      if (!this._supported) {
        this._state = { kind: EyeReadingKind.Unsupported };
      } else if (!hadUnwatchTimer && !this._enableRefused) {
        this._state = { kind: EyeReadingKind.Waiting };
        this._desiredOn = true;
        this._syncNotifications();
      }
    }

    this._invokeListener(listener, this._state);

    let active = true;
    return () => {
      if (!active) return;
      active = false;
      if (this._closed) return;
      const index = this._listeners.indexOf(entry);
      if (index !== -1) this._listeners.splice(index, 1);
      if (this._listeners.length === 0) {
        this._startUnwatchTimer();
      }
    };
  }

  acceptReading(reading) {
    if (this._closed || this._listeners.length === 0) return;
    this._restartStaleTimer();
    this._setState({ kind: EyeReadingKind.Reading, reading });
  }

  close() {
    if (this._closed) return;
    this._closed = true;
    this._clearStaleTimer();
    this._clearUnwatchTimer();
    if (this._removeRoute) {
      this._removeRoute();
      this._removeRoute = null;
    }
    this._desiredOn = false;
    this._state = null;
    const snapshot = this._listeners.slice();
    this._listeners = [];
    for (let i = 0; i < snapshot.length; i++) {
      this._invokeListener(snapshot[i].fn, null);
    }
  }

  _invokeListener(listener, state) {
    try {
      listener(state);
    } catch (err) {
      console.log('EyeStatusWatcher listener failed:', err);
    }
  }

  _emitToAll(state) {
    const snapshot = this._listeners.slice();
    for (let i = 0; i < snapshot.length; i++) {
      this._invokeListener(snapshot[i].fn, state);
    }
  }

  _setState(nextState) {
    const prev = this._state;
    const isReading = nextState !== null && nextState.kind === EyeReadingKind.Reading;
    if (!isReading) {
      const prevKind = prev ? prev.kind : null;
      const nextKind = nextState ? nextState.kind : null;
      if (prevKind === nextKind) return;
    }
    this._state = nextState;
    this._emitToAll(nextState);
  }

  _clearStaleTimer() {
    if (this._staleTimer !== null) {
      clearTimeout(this._staleTimer);
      this._staleTimer = null;
    }
  }

  _restartStaleTimer() {
    this._clearStaleTimer();
    this._staleTimer = setTimeout(() => {
      this._staleTimer = null;
      if (this._closed) return;
      this._setState({ kind: EyeReadingKind.Waiting });
    }, EYE_STALE_MS);
  }

  _clearUnwatchTimer() {
    if (this._unwatchTimer !== null) {
      clearTimeout(this._unwatchTimer);
      this._unwatchTimer = null;
    }
  }

  _startUnwatchTimer() {
    this._clearUnwatchTimer();
    this._unwatchTimer = setTimeout(() => {
      this._unwatchTimer = null;
      if (this._closed || this._listeners.length > 0) return;
      this._clearStaleTimer();
      this._enableRefused = false;
      this._desiredOn = false;
      if (this._supported) {
        this._setState(null);
      } else {
        this._setState({ kind: EyeReadingKind.Unsupported });
      }
      this._syncNotifications();
    }, EYE_UNWATCH_DELAY_MS);
  }

  _syncNotifications() {
    if (this._closed || !this._supported || this._writeInFlight) return;
    const target = this._desiredOn && !this._enableRefused;
    if (target === this._notificationsOn) return;

    this._writeInFlight = true;
    let writePromise;
    try {
      writePromise = Promise.resolve(this._setNotifications(target));
    } catch (err) {
      writePromise = Promise.reject(err);
    }

    writePromise.then(
      () => {
        if (this._closed) return;
        this._writeInFlight = false;
        this._notificationsOn = target;
        if (target && this._desiredOn && this._listeners.length > 0) {
          this._restartStaleTimer();
        }
        this._syncNotifications();
      },
      (err) => {
        if (this._closed) return;
        this._writeInFlight = false;
        this._notificationsOn = false;
        if (target) {
          // A timeout means the link did not answer, so report Failed.
          // Any other rejection means the stack refused the CCC write, which on
          // this watch shows that the board has no eye characteristic.
          if (this._listeners.length > 0 || this._unwatchTimer !== null) {
            this._enableRefused = true;
            this._desiredOn = false;
            const nextKind = isTransportTimeout(err)
              ? EyeReadingKind.Failed
              : EyeReadingKind.Unsupported;
            this._setState({ kind: nextKind });
          }
        } else {
          console.log('Eye status unsubscribe failed:', err);
        }
        this._syncNotifications();
      }
    );
  }
}
