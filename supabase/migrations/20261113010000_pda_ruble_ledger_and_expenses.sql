-- Shared ruble ledger and child expense tracking.
-- See the Supabase migration history for the applied SQL.
create table if not exists public.pda_wallet_ledger (
  id uuid primary key default gen_random_uuid(),
  movement_id uuid not null,
  user_id uuid not null references auth.users(id),
  counterparty_user_id uuid references auth.users(id),
  conversation_id uuid references public.pda_conversations(id),
  movement_type text not null check (movement_type in ('artifact_income','reward_sent','reward_received','expense')),
  amount bigint not null check (amount > 0),
  description text not null default '',
  balance_after bigint,
  created_at timestamptz not null default now()
);
create index if not exists pda_wallet_ledger_user_created_idx on public.pda_wallet_ledger(user_id,created_at desc);
create index if not exists pda_wallet_ledger_conversation_created_idx on public.pda_wallet_ledger(conversation_id,created_at desc);
create index if not exists pda_wallet_ledger_counterparty_idx on public.pda_wallet_ledger(counterparty_user_id);
create unique index if not exists pda_wallet_ledger_movement_user_unique on public.pda_wallet_ledger(movement_id,user_id);
alter table public.pda_wallet_ledger enable row level security;
revoke all on public.pda_wallet_ledger from anon,authenticated,public;
grant all on public.pda_wallet_ledger to service_role;

create or replace function public.pda_credit_ru(p_user uuid,p_amount bigint,p_initial_balance bigint,p_operation_id uuid)
returns bigint language plpgsql set search_path to 'public' as $function$
declare v_inserted uuid; v_balance bigint;
begin
 if p_amount<=0 or p_amount>1000000 or p_initial_balance<0 or p_initial_balance>1000000000 then raise exception 'Importe o saldo inválido'; end if;
 insert into pda_wallets(user_id,balance) values(p_user,p_initial_balance) on conflict(user_id) do nothing;
 insert into pda_wallet_credits(operation_id,user_id,amount) values(p_operation_id,p_user,p_amount) on conflict(operation_id) do nothing returning operation_id into v_inserted;
 if v_inserted is not null then
  update pda_wallets set balance=balance+p_amount,updated_at=now() where user_id=p_user returning balance into v_balance;
  insert into pda_wallet_ledger(movement_id,user_id,movement_type,amount,description,balance_after) values(p_operation_id,p_user,'artifact_income',p_amount,'Artefactos entregados a Barbas',v_balance);
 else select balance into v_balance from pda_wallets where user_id=p_user; end if;
 return v_balance;
end;$function$;

create or replace function public.pda_transfer_ru(p_sender uuid,p_recipient uuid,p_conversation_id uuid,p_amount bigint,p_initial_balance bigint)
returns table(sender_balance bigint,recipient_balance bigint) language plpgsql set search_path to 'public' as $function$
declare v_sender_balance bigint; v_recipient_balance bigint; v_event_id uuid; v_message text;
begin
 if p_sender<>'d08f76db-d3a0-4705-a075-ea1f5426e1d0'::uuid or p_recipient<>'f7b0836a-e88b-4d5a-9073-070ccece982d'::uuid then raise exception 'PDA no autorizada para esta transferencia'; end if;
 if p_amount not in (500,1000,2000,5000,10000) then raise exception 'Importe de transferencia no permitido'; end if;
 if p_initial_balance<0 or p_initial_balance>1000000000 then raise exception 'Saldo inicial inválido'; end if;
 if not exists(select 1 from pda_conversation_members a join pda_conversation_members b on b.conversation_id=a.conversation_id where a.conversation_id=p_conversation_id and a.user_id=p_sender and b.user_id=p_recipient) then raise exception 'Las PDA no están vinculadas'; end if;
 insert into pda_wallets(user_id,balance) values(p_sender,p_initial_balance),(p_recipient,0) on conflict(user_id) do nothing;
 perform 1 from pda_wallets where user_id in (p_sender,p_recipient) order by user_id for update;
 select balance into v_sender_balance from pda_wallets where user_id=p_sender;
 if v_sender_balance<p_amount then raise exception 'Saldo insuficiente'; end if;
 update pda_wallets set balance=balance-p_amount,updated_at=now() where user_id=p_sender returning balance into v_sender_balance;
 update pda_wallets set balance=balance+p_amount,updated_at=now() where user_id=p_recipient returning balance into v_recipient_balance;
 insert into pda_wallet_events(sender_user_id,recipient_user_id,conversation_id,event_type,amount,body) values(p_sender,p_recipient,p_conversation_id,'transfer',p_amount,'Yuri te ha enviado una recompensa. ¡Buen trabajo, muchacho!') returning id into v_event_id;
 insert into pda_wallet_ledger(movement_id,user_id,counterparty_user_id,conversation_id,movement_type,amount,description,balance_after)
 values(v_event_id,p_sender,p_recipient,p_conversation_id,'reward_sent',p_amount,'Recompensa enviada a tu hijo',v_sender_balance),(v_event_id,p_recipient,p_sender,p_conversation_id,'reward_received',p_amount,'Recompensa recibida de Yuri',v_recipient_balance);
 v_message:=format('[[PDA_YURI_EVENT:transfer:%s:%s]]%s',p_amount,v_event_id,'Yuri te ha enviado una recompensa. ¡Buen trabajo, muchacho!');
 insert into pda_messages(conversation_id,sender_id,message_type,body) values(p_conversation_id,p_sender,'text',v_message);
 return query select v_sender_balance,v_recipient_balance;
end;$function$;

create or replace function public.pda_spend_ru(p_user uuid,p_conversation_id uuid,p_amount bigint,p_description text)
returns bigint language plpgsql set search_path to 'public' as $function$
declare v_balance bigint; v_movement uuid:=gen_random_uuid(); v_description text;
begin
 if p_user<>'f7b0836a-e88b-4d5a-9073-070ccece982d'::uuid then raise exception 'Solo la PDA del destinatario puede registrar gastos'; end if;
 if p_amount<=0 or p_amount>100000000 then raise exception 'Importe de gasto inválido'; end if;
 v_description:=left(trim(coalesce(p_description,'')),120); if v_description='' then v_description:='Gasto registrado'; end if;
 if not exists(select 1 from pda_conversation_members a join pda_conversation_members b on b.conversation_id=a.conversation_id where a.conversation_id=p_conversation_id and a.user_id='d08f76db-d3a0-4705-a075-ea1f5426e1d0'::uuid and b.user_id=p_user) then raise exception 'Las PDA no están vinculadas'; end if;
 select balance into v_balance from pda_wallets where user_id=p_user for update;
 if v_balance is null then raise exception 'La billetera todavía no está inicializada'; end if;
 if v_balance<p_amount then raise exception 'Saldo insuficiente'; end if;
 update pda_wallets set balance=balance-p_amount,updated_at=now() where user_id=p_user returning balance into v_balance;
 insert into pda_wallet_ledger(movement_id,user_id,counterparty_user_id,conversation_id,movement_type,amount,description,balance_after) values(v_movement,p_user,'d08f76db-d3a0-4705-a075-ea1f5426e1d0'::uuid,p_conversation_id,'expense',p_amount,v_description,v_balance);
 return v_balance;
end;$function$;
revoke all on function public.pda_spend_ru(uuid,uuid,bigint,text) from public,anon,authenticated;
grant execute on function public.pda_spend_ru(uuid,uuid,bigint,text) to service_role;

insert into public.pda_wallet_ledger(movement_id,user_id,movement_type,amount,description,balance_after,created_at)
select c.operation_id,c.user_id,'artifact_income',c.amount,'Artefactos entregados a Barbas',null,c.created_at from public.pda_wallet_credits c on conflict(movement_id,user_id) do nothing;
insert into public.pda_wallet_ledger(movement_id,user_id,counterparty_user_id,conversation_id,movement_type,amount,description,balance_after,created_at)
select e.id,e.sender_user_id,e.recipient_user_id,e.conversation_id,'reward_sent',e.amount,'Recompensa enviada a tu hijo',null,e.created_at from public.pda_wallet_events e where e.event_type='transfer' on conflict(movement_id,user_id) do nothing;
insert into public.pda_wallet_ledger(movement_id,user_id,counterparty_user_id,conversation_id,movement_type,amount,description,balance_after,created_at)
select e.id,e.recipient_user_id,e.sender_user_id,e.conversation_id,'reward_received',e.amount,'Recompensa recibida de Yuri',null,e.created_at from public.pda_wallet_events e where e.event_type='transfer' on conflict(movement_id,user_id) do nothing;
