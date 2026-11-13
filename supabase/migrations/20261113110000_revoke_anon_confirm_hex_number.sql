-- Defense in depth: confirm_hex_number checks auth.uid() internally, but its
-- EXECUTE privilege leaked to anon and PUBLIC. Activation belongs to signed-in
-- accounts only, so the grant is narrowed to match reserve_hex_number.
REVOKE ALL ON FUNCTION public.confirm_hex_number(text) FROM anon, PUBLIC;
GRANT EXECUTE ON FUNCTION public.confirm_hex_number(text) TO authenticated;
