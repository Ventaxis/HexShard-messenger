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

    let payload: any;
    try {
      payload = await req.json();
    } catch (_e) {
      return new Response(
        JSON.stringify({ error: "Invalid JSON body" }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    const rawNumber = payload?.raw_number?.toString().trim() || payload?.p_raw_number?.toString().trim();
    if (!rawNumber || !/^\d{8}$/.test(rawNumber.replace(/\D/g, ""))) {
      return new Response(
        JSON.stringify({ error: "A valid 8-digit virtual number is required" }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    const userClient = getUserClient(authHeader);

    // Call atomic stored procedure confirm_hex_number
    const { data, error } = await userClient.rpc("confirm_hex_number", {
      p_raw_number: rawNumber,
    });

    if (error) {
      let status = 500;
      if (error.code === "23505") status = 409;
      else if (error.code === "22023") status = 400;
      else if (error.code === "42501") status = 403;
      else if (error.code === "P0002") status = 404;

      return new Response(
        JSON.stringify({ error: error.message || "Failed to confirm virtual number", code: error.code }),
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
