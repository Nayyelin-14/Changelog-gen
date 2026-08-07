import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Briefcase, Sparkles, Terminal } from 'lucide-react';

import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { getStoredRole, setStoredRole, type Role } from '@/lib/role';

const ROLES: { key: Role; icon: typeof Terminal; label: string; description: string; blurb: string }[] = [
  {
    key: 'dev',
    icon: Terminal,
    label: 'Developer',
    description: 'Full changelog management',
    blurb: 'Browse repos, generate changelogs, edit, and manage release history.',
  },
  {
    key: 'qa',
    icon: Sparkles,
    label: 'QA',
    description: 'What to test',
    blurb: 'See what changed and what needs verification for each release.',
  },
  {
    key: 'business',
    icon: Briefcase,
    label: 'Business',
    description: 'Executive summaries',
    blurb: 'Plain-language overview of what shipped, for stakeholders.',
  },
];

export function RoleSelectPage() {
  const navigate = useNavigate();
  const [ready, setReady] = useState(false);

  useEffect(() => {
    const stored = getStoredRole();
    if (stored) {
      const path = stored === 'dev' ? '/dev' : `/${stored}`;
      navigate(path, { replace: true });
    } else {
      setReady(true);
    }
  }, [navigate]);

  if (!ready) return null;

  return (
    <div className="mx-auto max-w-lg space-y-6 py-12">
      <div className="text-center">
        <div className="flex items-center justify-center gap-1.5 text-[10px] font-medium text-primary">
          <Sparkles className="size-3" />
          Changelog Composer
        </div>
        <h1 className="mt-2 text-2xl font-bold tracking-tight text-gradient">Choose your view</h1>
        <p className="mt-1 text-xs text-muted-foreground">
          Pick the role that fits what you need to see.
        </p>
      </div>

      <div className="space-y-3">
        {ROLES.map((role) => {
          const Icon = role.icon;
          return (
            <button
              key={role.key}
              type="button"
              onClick={() => {
                setStoredRole(role.key);
                const path = role.key === 'dev' ? '/dev' : `/${role.key}`;
                navigate(path, { replace: true });
              }}
              className="group w-full text-left"
            >
              <Card className="transition-all hover:-translate-y-0.5 hover:border-primary/40 hover:shadow-lg hover:shadow-primary/10">
                <CardHeader className="flex flex-row items-center gap-3 pb-2">
                  <div className="flex size-10 shrink-0 items-center justify-center rounded-xl bg-primary/10 text-primary transition-colors group-hover:bg-primary/15">
                    <Icon className="size-5" />
                  </div>
                  <div>
                    <CardTitle className="text-sm">{role.label}</CardTitle>
                    <CardDescription className="text-xs">{role.description}</CardDescription>
                  </div>
                </CardHeader>
                <CardContent>
                  <p className="text-xs text-muted-foreground">{role.blurb}</p>
                </CardContent>
              </Card>
            </button>
          );
        })}
      </div>
    </div>
  );
}
