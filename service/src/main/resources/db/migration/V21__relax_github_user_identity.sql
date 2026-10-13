-- Identity hardening on github_user, required by the multi-user ownership model.
--
-- 1. Drop UNIQUE(login). GitHub usernames are mutable and, once freed, can be claimed by a
--    completely different person. Looking a user up by login — or letting login be a unique
--    key — would let that new person inherit the original user's changelogs. Identity is
--    therefore github_id only (stable across renames); login is a display snapshot that is
--    refreshed on every sign-in.
--
-- 2. Make access_token nullable. "Disconnect GitHub" revokes the OAuth token server-side and
--    clears it, but keeps the github_user row so that (a) the user's owned rows keep pointing
--    at a live owner and (b) signing in again with the same GitHub account reattaches to the
--    same app account instead of silently creating a second one.

ALTER TABLE github_user DROP CONSTRAINT IF EXISTS uq_github_user_login;
ALTER TABLE github_user ALTER COLUMN access_token DROP NOT NULL;
