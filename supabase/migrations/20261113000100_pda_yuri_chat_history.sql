-- Persist Yuri reward transfers and recognition as ordinary shared chat records.
-- The marker lets both PDA clients render a distinctive card while retaining the
-- regular pda_messages history, sender identity, ordering, and realtime behavior.

create or replace function public.pda_transfer_ru(
  p_sender uuid,
  p_recipient uuid,
  p_conversation_id uuid,
  p_amount bigint,
  p_initial_balance bigint
)
returns table(sender_balance bigint, recipient_balance bigint)
language plpgsql
set search_path to 'public'
as $function$
declare
  v_sender_balance bigint;
  v_recipient_balance bigint;
  v_event_id uuid;
  v_message text;
begin
  if p_sender <> 'd08f76db-d3a0-4705-a075-ea1f5426e1d0'::uuid
     or p_recipient <> 'f7b0836a-e88b-4d5a-9073-070ccece982d'::uuid then
    raise exception 'PDA no autorizada para esta transferencia';
  end if;
  if p_amount not in (500,1000,2000,5000,10000) then
    raise exception 'Importe de transferencia no permitido';
  end if;
  if p_initial_balance < 0 or p_initial_balance > 1000000000 then
    raise exception 'Saldo inicial inválido';
  end if;
  if not exists (
    select 1 from pda_conversation_members a
    join pda_conversation_members b on b.conversation_id = a.conversation_id
    where a.conversation_id = p_conversation_id
      and a.user_id = p_sender and b.user_id = p_recipient
  ) then
    raise exception 'Las PDA no están vinculadas';
  end if;
  insert into pda_wallets(user_id,balance)
  values (p_sender,p_initial_balance),(p_recipient,0)
  on conflict (user_id) do nothing;
  perform 1 from pda_wallets
   where user_id in (p_sender,p_recipient)
   order by user_id
   for update;
  select balance into v_sender_balance from pda_wallets where user_id=p_sender;
  if v_sender_balance < p_amount then
    raise exception 'Saldo insuficiente';
  end if;
  update pda_wallets
    set balance=balance-p_amount,updated_at=now()
    where user_id=p_sender
    returning balance into v_sender_balance;
  update pda_wallets
    set balance=balance+p_amount,updated_at=now()
    where user_id=p_recipient
    returning balance into v_recipient_balance;
  insert into pda_wallet_events(sender_user_id,recipient_user_id,conversation_id,event_type,amount,body)
  values (p_sender,p_recipient,p_conversation_id,'transfer',p_amount,'Yuri te ha enviado una recompensa. ¡Buen trabajo, muchacho!')
  returning id into v_event_id;
  v_message := format('[[PDA_YURI_EVENT:transfer:%s:%s]]%s',p_amount,v_event_id,'Yuri te ha enviado una recompensa. ¡Buen trabajo, muchacho!');
  insert into pda_messages(conversation_id,sender_id,message_type,body)
  values (p_conversation_id,p_sender,'text',v_message);
  return query select v_sender_balance,v_recipient_balance;
end;
$function$;

create or replace function public.pda_send_yuri_recognition(
  p_sender uuid,
  p_recipient uuid,
  p_conversation_id uuid,
  p_body text
)
returns uuid
language plpgsql
set search_path to 'public'
as $function$
declare
  v_event_id uuid;
  v_expected text := 'Me llegó la noticia de que cumpliste con tus tareas. El esfuerzo merece una recompensa. Te dejo unos rublitos. No los gastes todos de golpe, muchacho. En la Zona siempre conviene tener reservas.';
begin
  if p_sender <> 'd08f76db-d3a0-4705-a075-ea1f5426e1d0'::uuid
     or p_recipient <> 'f7b0836a-e88b-4d5a-9073-070ccece982d'::uuid
     or p_body <> v_expected then
    raise exception 'Reconocimiento no autorizado';
  end if;
  if not exists (
    select 1 from pda_conversation_members a
    join pda_conversation_members b on b.conversation_id = a.conversation_id
    where a.conversation_id = p_conversation_id
      and a.user_id = p_sender and b.user_id = p_recipient
  ) then
    raise exception 'Las PDA no están vinculadas';
  end if;
  insert into pda_wallet_events(sender_user_id,recipient_user_id,conversation_id,event_type,amount,body)
  values (p_sender,p_recipient,p_conversation_id,'yuri_message',null,p_body)
  returning id into v_event_id;
  insert into pda_messages(conversation_id,sender_id,message_type,body)
  values (p_conversation_id,p_sender,'text',
    format('[[PDA_YURI_EVENT:yuri_message:0:%s]]%s',v_event_id,p_body));
  return v_event_id;
end;
$function$;

revoke all on function public.pda_send_yuri_recognition(uuid,uuid,uuid,text) from public, anon, authenticated;
grant execute on function public.pda_send_yuri_recognition(uuid,uuid,uuid,text) to service_role;

-- Backfill prior Yuri events so the two already delivered transmissions also
-- appear permanently in both users' shared chat histories.
insert into public.pda_messages(conversation_id,sender_id,message_type,body,created_at)
select e.conversation_id,e.sender_user_id,'text',
       format('[[PDA_YURI_EVENT:%s:%s:%s]]%s',e.event_type,coalesce(e.amount,0),e.id,e.body),
       e.created_at
from public.pda_wallet_events e
where not exists (
  select 1 from public.pda_messages m
  where m.conversation_id=e.conversation_id
    and m.body like '%:'||e.id::text||']]%'
);
