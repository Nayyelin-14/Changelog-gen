import { Outlet } from 'react-router-dom';

import { Header } from '@/components/Header';

export function Layout() {
  return (
    <div className="relative flex h-screen flex-col overflow-hidden bg-background">
      <div className="pointer-events-none fixed inset-0 -z-10 overflow-hidden">
        <div className="absolute -top-32 -left-24 size-96 rounded-full bg-primary/15 blur-3xl animate-float" />
        <div
          className="absolute top-1/3 -right-32 size-[28rem] rounded-full bg-accent/40 blur-3xl animate-float"
          style={{ animationDelay: '1.5s' }}
        />
        <div
          className="absolute bottom-0 left-1/4 size-80 rounded-full bg-primary/10 blur-3xl animate-float"
          style={{ animationDelay: '3s' }}
        />
      </div>
      <Header />
      {/* main is the ONE scroll container for pages that overflow it (their content just sizes
          naturally). Pages that opt into filling the screen (flex-1 min-h-0 down the tree) get
          a real bounded height here instead — that's what lets their own inner lists scroll
          internally instead of the whole page growing taller than the viewport. */}
      <main className="flex flex-1 flex-col overflow-y-auto">
        <div className="mx-auto flex h-full min-h-0 w-full max-w-6xl flex-1 flex-col px-6 py-4">
          <Outlet />
        </div>
      </main>
    </div>
  );
}
