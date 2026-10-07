import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { corsHeaders } from "../_shared/cors.ts";
import { getAuthenticatedUser, getUserClient } from "../_shared/supabaseClient.ts";

serve(async (req: Request) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  try {
    const authHeader = req.headers.get("Authorization");
    const user = await getAuthenticatedUser(req);
    if (!user || !authHeader) {
      return new Response(
        JSON.stringify({ error: "Unauthorized: User authentication required" }),
        { status: 401, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    let payload: any = {};
    try {
      if (req.headers.get("content-length") !== "0") {
        payload = await req.json();
      }
    } catch (_e) {
      // payload is optional
    }

    const preferred = payload?.preferred?.toString().trim() || payload?.p_preferred?.toString().trim();
    const userClient = getUserClient(authHeader);

    // Call atomic canonical stored procedure reserve_hex_number
    const { data, error } = await userClient.rpc("reserve_hex_number", {
      p_preferred: preferred || null,
    });

    if (error) {
      let status = 500;
      if (error.code === "23505") status = 409; // Conflict: already taken
      else if (error.code === "22023") status = 400; // Invalid input: not 8 digits
      else if (error.code === "42501") status = 403; // Unauthorized
      else if (error.code === "P0002") status = 404; // No available numbers

      return new Response(
        JSON.stringify({ error: error.message || "Failed to reserve virtual number", code: error.code }),
        { status, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    return new Response(
      JSON.stringify(data),
      { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  } catch (_e) {
    return new Response(
      JSON.stringify({ error: "Internal server error" }),
      { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  }
});
