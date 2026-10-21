-- Daily cleanup of passenger stops (Supabase ships pg_cron; enable it under Database > Extensions).
create extension if not exists pg_cron;

select cron.schedule('fairpool-purge-locations', '15 3 * * *', $$select public.purge_old_locations()$$);
