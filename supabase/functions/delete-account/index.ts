import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { corsHeaders } from "../_shared/cors.ts";
import { getAuthenticatedUser, getServiceClient, getUserClient } from "../_shared/supabaseClient.ts";

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

    const userId = user.id;
    const userClient = getUserClient(authHeader);
    const serviceClient = getServiceClient();

    // 1. Run database cleanup RPC
    try {
      await userClient.rpc("delete_user_account");
    } catch (_e) {
      // Fallback direct cleanup with service client
      await serviceClient.from("attachments").delete().eq("owner_id", userId);
      await serviceClient.from("messages").delete().eq("sender_id", userId);
      await serviceClient.from("conversation_members").delete().eq("account_id", userId);
      await serviceClient.from("devices").delete().eq("account_id", userId);
      await serviceClient.from("telegram_links").delete().eq("account_id", userId);
      await serviceClient.from("telegram_challenges").delete().eq("account_id", userId);
      await serviceClient.from("hex_numbers").update({ status: "cooldown", owner_id: null }).eq("owner_id", userId);
      await serviceClient.from("profiles").delete().eq("id", userId);
    }

    // 2. Delete user from auth.users via service role admin API
    const { error: deleteAuthErr } = await serviceClient.auth.admin.deleteUser(userId);
    if (deleteAuthErr && !deleteAuthErr.message?.toLowerCase().includes("not found")) {
      console.error("Error deleting auth user:", deleteAuthErr);
      return new Response(
        JSON.stringify({ error: "Failed to delete user account auth credentials", details: deleteAuthErr.message }),
        { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    return new Response(
      JSON.stringify({ success: true, message: "Account successfully and permanently deleted" }),
      { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  } catch (_e) {
    return new Response(
      JSON.stringify({ error: "Internal server error during account deletion" }),
      { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  }
});
