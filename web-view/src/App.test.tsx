import { render, screen } from '@testing-library/react'
import { describe, it, expect, vi } from 'vitest'

// The smoke test renders the whole shell — its mount-time network reads (/auth/me and
// /ai/providers) must be stubbed or they reject as unhandled errors against a dead server.
vi.mock('@/api/auth', () => ({
  getAuthMe: vi.fn(async () => null),
  signOut: vi.fn(async () => {}),
  deleteGithubAccount: vi.fn(async () => {}),
  disconnectGithub: vi.fn(async () => {}),
  buildGithubSignInUrl: vi.fn(() => '/api/auth/github'),
  buildGithubConnectUrl: vi.fn(() => '/api/auth/github?mode=connect'),
}))

vi.mock('@/api/client', async (importOriginal) => {
  const mod = await importOriginal<typeof import('@/api/client')>()
  return {
    ...mod,
    listAiProviders: vi.fn(async () => []),
    listAiModels: vi.fn(async () => []),
  }
})

import App from './App'

describe('App', () => {
  it('renders without crashing', () => {
    render(<App />)
    expect(screen.getByRole('heading', { name: /choose your view/i })).toBeInTheDocument()
  })
})
