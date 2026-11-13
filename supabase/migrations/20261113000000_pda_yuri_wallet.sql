-- PDA // ZONA · billetera de Yuri y transferencias entre PDA
create table if not exists public.pda_wallets (
  user_id uuid primary key references auth.users(id) on delete cascade,
  balance bigint not null default 0 check (balance >= 0),
  updated_at timestamptz not null default now()
);
alter table public.pda_wallets enable row level security;
revoke all on public.pda_wallets from public, anon, authenticated;
grant all on public.pda_wallets to service_role;

create table if not exists public.pda_wallet_events (
  id uuid primary key default gen_random_uuid(),
  sender_user_id uuid not null references auth.users(id) on delete cascade,
  recipient_user_id uuid not null references auth.users(id) on delete cascade,
  conversation_id uuid not null references public.pda_conversations(id) on delete cascade,
  event_type text not null check (event_type in ('transfer','yuri_message')),
  amount bigint check (amount is null or amount > 0),
  body text not null default '',
  created_at timestamptz not null default now()
);
create index if not exists pda_wallet_events_recipient_created_idx
  on public.pda_wallet_events(recipient_user_id, created_at desc);
alter table public.pda_wallet_events enable row level security;
revoke all on public.pda_wallet_events from public, anon, authenticated;
grant all on public.pda_wallet_events to service_role;

create table if not exists public.pda_wallet_credits (
  operation_id uuid primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  amount bigint not null check (amount > 0),
  created_at timestamptz not null default now()
);
alter table public.pda_wallet_credits enable row level security;
revoke all on public.pda_wallet_credits from public, anon, authenticated;
grant all on public.pda_wallet_credits to service_role;

create or replace function public.pda_transfer_ru(
  p_sender uuid, p_recipient uuid, p_conversation_id uuid, p_amount bigint, p_initial_balance bigint
)
returns table(sender_balance bigint, recipient_balance bigint)
language plpgsql security invoker set search_path = public
as $$
declare v_sender_balance bigint; v_recipient_balance bigint;
begin
  if p_amount not in (500,1000,2000,5000,10000) then raise exception 'Importe de transferencia no permitido'; end if;
  if p_initial_balance < 0 or p_initial_balance > 1000000000 then raise exception 'Saldo inicial inválido'; end if;
  if not exists (
    select 1 from pda_conversation_members a join pda_conversation_members b on b.conversation_id=a.conversation_id
    where a.conversation_id=p_conversation_id and a.user_id=p_sender and b.user_id=p_recipient
  ) then raise exception 'Las PDA no están vinculadas'; end if;
  insert into pda_wallets(user_id,balance) values(p_sender,p_initial_balance),(p_recipient,0) on conflict(user_id) do nothing;
  perform 1 from pda_wallets where user_id in(p_sender,p_recipient) order by user_id for update;
  select balance into v_sender_balance from pda_wallets where user_id=p_sender;
  if v_sender_balance < p_amount then raise exception 'Saldo insuficiente'; end if;
  update pda_wallets set balance=balance-p_amount,updated_at=now() where user_id=p_sender returning balance into v_sender_balance;
  update pda_wallets set balance=balance+p_amount,updated_at=now() where user_id=p_recipient returning balance into v_recipient_balance;
  insert into pda_wallet_events(sender_user_id,recipient_user_id,conversation_id,event_type,amount,body)
  values(p_sender,p_recipient,p_conversation_id,'transfer',p_amount,'Yuri te ha enviado una recompensa. ¡Buen trabajo, muchacho!');
  return query select v_sender_balance,v_recipient_balance;
end;
$$;
revoke all on function public.pda_transfer_ru(uuid,uuid,uuid,bigint,bigint) from public,anon,authenticated;
grant execute on function public.pda_transfer_ru(uuid,uuid,uuid,bigint,bigint) to service_role;

create or replace function public.pda_credit_ru(p_user uuid,p_amount bigint,p_initial_balance bigint,p_operation_id uuid)
returns bigint language plpgsql security invoker set search_path = public
as $$
declare v_inserted uuid; v_balance bigint;
begin
  if p_amount<=0 or p_amount>1000000 or p_initial_balance<0 or p_initial_balance>1000000000 then raise exception 'Importe o saldo inválido'; end if;
  insert into pda_wallets(user_id,balance) values(p_user,p_initial_balance) on conflict(user_id) do nothing;
  insert into pda_wallet_credits(operation_id,user_id,amount) values(p_operation_id,p_user,p_amount)
    on conflict(operation_id) do nothing returning operation_id into v_inserted;
  if v_inserted is not null then update pda_wallets set balance=balance+p_amount,updated_at=now() where user_id=p_user; end if;
  select balance into v_balance from pda_wallets where user_id=p_user;
  return v_balance;
end;
$$;
revoke all on function public.pda_credit_ru(uuid,bigint,bigint,uuid) from public,anon,authenticated;
grant execute on function public.pda_credit_ru(uuid,bigint,bigint,uuid) to service_role;

create index if not exists pda_wallet_events_conversation_idx on public.pda_wallet_events(conversation_id);
create index if not exists pda_wallet_events_sender_idx on public.pda_wallet_events(sender_user_id);
create index if not exists pda_wallet_credits_user_idx on public.pda_wallet_credits(user_id);
