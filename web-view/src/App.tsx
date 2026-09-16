import { HashRouter, Route, Routes } from 'react-router-dom';
import { Toaster } from 'sonner';

import { AuthProvider } from '@/api/AuthContext';
import { Layout } from '@/components/Layout';
import { RouteGuard } from '@/components/RouteGuard';
import { AudienceBrowsePage } from '@/pages/AudienceBrowsePage';
import { AudienceChangelogPage } from '@/pages/AudienceChangelogPage';
import { GenerateChangelogPage } from '@/pages/GenerateChangelogPage';
import { GenerateNewChangelogPage } from '@/pages/GenerateNewChangelogPage';
import { LoginPage } from '@/pages/LoginPage';
import { NotFoundPage } from '@/pages/NotFoundPage';
import { PrRedirectPage } from '@/pages/PrRedirectPage';
import { ProjectsPage } from '@/pages/ProjectsPage';
import { RoleSelectPage } from '@/pages/RoleSelectPage';

function App() {
  return (
    <AuthProvider>
      <HashRouter>
        <Toaster richColors closeButton position="bottom-right" />
        <Routes>
          <Route element={<Layout />}>
            <Route index element={<RoleSelectPage />} />
            <Route path="login" element={<LoginPage />} />
            <Route path="dev" element={<RouteGuard role="dev"><ProjectsPage /></RouteGuard>} />
            <Route path="dev/projects/:project" element={<RouteGuard key="project" role="dev"><GenerateChangelogPage /></RouteGuard>} />
            <Route path="dev/projects/:project/repos/:repo" element={<RouteGuard key="repo" role="dev"><GenerateChangelogPage /></RouteGuard>} />
            <Route path="dev/projects/:project/repos/:repo/generate" element={<RouteGuard key="generate" role="dev"><GenerateNewChangelogPage /></RouteGuard>} />
            <Route path="dev/projects/:project/repos/:repo/history" element={<RouteGuard key="history" role="dev"><GenerateChangelogPage /></RouteGuard>} />
            <Route path="dev/projects/:project/repos/:repo/history/:entryId" element={<RouteGuard key="history-entry" role="dev"><GenerateChangelogPage /></RouteGuard>} />
            <Route path="qa" element={<RouteGuard role="qa"><AudienceBrowsePage audience="qa" /></RouteGuard>} />
            <Route path="qa/:project/:repo" element={<RouteGuard role="qa"><AudienceChangelogPage roleBase="qa" audiences={['qa', 'business']} /></RouteGuard>} />
            <Route path="business" element={<RouteGuard role="business"><AudienceBrowsePage audience="business" /></RouteGuard>} />
            <Route path="business/:project/:repo" element={<RouteGuard role="business"><AudienceChangelogPage roleBase="business" audiences={['business']} /></RouteGuard>} />
            {/* Unguarded: an external dashboard's cold link has no role picked yet, and there's no
                real auth here to protect anyway — see PrRedirectPage. */}
            <Route path="pr/:project/:repo/:prId" element={<PrRedirectPage />} />
            <Route path="*" element={<NotFoundPage />} />
          </Route>
        </Routes>
      </HashRouter>
    </AuthProvider>
  );
}

export default App;