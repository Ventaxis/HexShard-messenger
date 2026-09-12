// Shared Supabase client generator for Edge Functions
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.45.4";

export function getServiceClient() {
  const supabaseUrl = Deno.env.get("SUPABASE_URL") ?? "";
  const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
  return createClient(supabaseUrl, serviceRoleKey, {
    auth: {
      persistSession: false,
      autoRefreshToken: false,
    },
  });
}

export function getUserClient(authHeader: string) {
  const supabaseUrl = Deno.env.get("SUPABASE_URL") ?? "";
  const anonKey = Deno.env.get("SUPABASE_ANON_KEY") ?? "";
  return createClient(supabaseUrl, anonKey, {
    global: {
      headers: {
        Authorization: authHeader,
      },
    },
    auth: {
      persistSession: false,
      autoRefreshToken: false,
    },
  });
}

export async function getAuthenticatedUser(req: Request) {
  const authHeader = req.headers.get("Authorization");
  if (!authHeader || !authHeader.startsWith("Bearer ")) {
    return null;
  }
  const token = authHeader.substring(7).trim();
  if (!token) {
    return null;
  }

  // 1. Try using the user client
  try {
    const userClient = getUserClient(authHeader);
    const { data: { user }, error } = await userClient.auth.getUser();
    if (!error && user) {
      return user;
    }
  } catch (_e) {
    // Fallback
  }

  // 2. Fallback to direct token validation via service client
  try {
    const serviceClient = getServiceClient();
    const { data: { user }, error } = await serviceClient.auth.getUser(token);
    if (!error && user) {
      return user;
    }
  } catch (_e) {
    return null;
  }

  return null;
}
