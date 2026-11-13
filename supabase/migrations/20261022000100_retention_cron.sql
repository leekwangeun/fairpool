-- Hourly cleanup of passenger stops and place names (Supabase ships pg_cron; enable it under Database > Extensions).
-- Every hour, so nothing is kept much longer than the 24 hours passengers are told.
create extension if not exists pg_cron;

select cron.schedule('fairpool-purge-locations', '15 * * * *', $$select public.purge_old_locations()$$);
