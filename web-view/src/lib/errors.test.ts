import { describe, it, expect } from 'vitest';

import { ApiError } from '@/api/client';
import { friendlyApiError } from '@/lib/errors';

describe('friendlyApiError', () => {
  it('maps 401 to session-expired copy', () => {
    expect(friendlyApiError(new ApiError('401 not_authenticated', 401), 'fallback')).toBe(
      'Your session has expired. Please sign in again.',
    );
  });

  it('maps 403 to ownership/permission copy', () => {
    expect(friendlyApiError(new ApiError('403 forbidden', 403), 'fallback')).toBe(
      "You don't have permission to modify this changelog — it may belong to another account.",
    );
  });

  it('maps 404 to not-found copy', () => {
    expect(friendlyApiError(new ApiError('404 gone', 404), 'fallback')).toBe(
      'This changelog entry could not be found — it may have been deleted.',
    );
  });

  it('keeps server messages for other statuses', () => {
    expect(friendlyApiError(new ApiError('409 nothing to restore', 409), 'fallback')).toBe(
      '409 nothing to restore',
    );
  });

  it('falls back for non-ApiError values', () => {
    expect(friendlyApiError(undefined, 'something broke')).toBe('something broke');
    expect(friendlyApiError(new Error('plain'), 'something broke')).toBe('plain');
  });
});
