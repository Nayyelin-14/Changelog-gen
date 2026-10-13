import { render, screen } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';

import { AiModelSelect } from '@/components/AiModelSelect';
import { AiGenerationControls } from '@/components/AiGenerationControls';
import type { UseAiModelsReturn } from '@/hooks/useAiModels';

describe('AiModelSelect', () => {
  it('explains an unavailable/misconfigured provider and names it', () => {
    render(
      <AiModelSelect
        models={[]}
        status="error"
        value={undefined}
        onChange={vi.fn()}
        providerLabel="Groq"
      />,
    );

    expect(screen.getByText('Models unavailable for Groq')).toBeInTheDocument();
    expect(
      screen.getByText(/Generation is disabled until models load/i),
    ).toBeInTheDocument();
  });

  it('shows a neutral error without a provider label', () => {
    render(<AiModelSelect models={[]} status="error" value={undefined} onChange={vi.fn()} />);
    expect(screen.getByText('Models unavailable')).toBeInTheDocument();
  });

  it('shows the loading state while the catalog is in flight', () => {
    render(<AiModelSelect models={[]} status="loading" value={undefined} onChange={vi.fn()} />);
    expect(screen.getByText('Loading models…')).toBeInTheDocument();
  });
});

function fakeAi(overrides: Partial<UseAiModelsReturn> = {}): UseAiModelsReturn {
  return {
    providers: [
      { id: 'nvidia', label: 'NVIDIA NIM', enabled: true },
      { id: 'groq', label: 'Groq', enabled: true },
    ],
    providerOptions: [
      { id: 'nvidia', label: 'NVIDIA NIM', enabled: true },
      { id: 'groq', label: 'Groq', enabled: true },
    ],
    provider: 'groq',
    setProvider: vi.fn(),
    models: [],
    modelsStatus: 'error',
    model: undefined,
    setModel: vi.fn(),
    isReady: false,
    hasModels: false,
    ...overrides,
  };
}

describe('AiGenerationControls', () => {
  it('disables Generate when the provider’s model catalog failed to load', () => {
    render(<AiGenerationControls ai={fakeAi()} onGenerate={vi.fn()} />);

    expect(screen.getByRole('button', { name: /Generate/ })).toBeDisabled();
    expect(screen.getByText('Models unavailable for Groq')).toBeInTheDocument();
  });

  it('enables Generate once models are ready', () => {
    render(
      <AiGenerationControls
        ai={fakeAi({
          models: [{ id: 'groq-model', label: 'Groq Model' }],
          modelsStatus: 'success',
          model: 'groq-model',
          isReady: true,
          hasModels: true,
        })}
        onGenerate={vi.fn()}
      />,
    );

    expect(screen.getByRole('button', { name: /Generate/ })).toBeEnabled();
  });
});
