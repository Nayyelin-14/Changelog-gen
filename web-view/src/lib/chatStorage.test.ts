import { describe, it, expect, beforeEach } from 'vitest';

import {
  clearAllChatHistory,
  loadChatHistory,
  saveChatHistory,
  setChatUserScope,
} from '@/lib/chatStorage';

describe('chatStorage user scoping', () => {
  beforeEach(() => {
    localStorage.clear();
    setChatUserScope(null);
  });

  it('isolates threads per account', () => {
    setChatUserScope(1);
    saveChatHistory('p', 'r', '1.0.0', 'qa', [{ role: 'user', content: 'first user question' }]);

    setChatUserScope(2);
    expect(loadChatHistory('p', 'r', '1.0.0', 'qa')).toEqual([]);

    setChatUserScope(1);
    expect(loadChatHistory('p', 'r', '1.0.0', 'qa')).toHaveLength(1);
  });

  it('signed-out scope does not read a signed-in account thread', () => {
    setChatUserScope(1);
    saveChatHistory('p', 'r', '1.0.0', 'qa', [{ role: 'user', content: 'secret' }]);

    setChatUserScope(null);
    expect(loadChatHistory('p', 'r', '1.0.0', 'qa')).toEqual([]);
  });

  it('clearAllChatHistory removes only chat keys', () => {
    setChatUserScope(1);
    saveChatHistory('p', 'r', '1.0.0', 'qa', [{ role: 'user', content: 'hi' }]);
    localStorage.setItem('unrelated-key', 'keep-me');

    clearAllChatHistory();

    expect(loadChatHistory('p', 'r', '1.0.0', 'qa')).toEqual([]);
    expect(localStorage.getItem('unrelated-key')).toBe('keep-me');
  });
});
