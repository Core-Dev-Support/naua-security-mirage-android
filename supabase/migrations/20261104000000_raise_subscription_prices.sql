-- Raise the subscription prices: 40 / 110 / 200 / 330 ₽.
--
-- The app, the bot and the payment webhook all read this row, so changing it
-- here (or in the table editor) is enough; no APK rebuild or bot redeploy.
update public.app_config
set payload = $json$[
  {"id":"1m","days":30,"price_rub":40,"badge":null,"label_ru":"30 дней","label_en":"30 days"},
  {"id":"3m","days":90,"price_rub":110,"badge":"-8%","label_ru":"3 месяца","label_en":"3 months"},
  {"id":"6m","days":180,"price_rub":200,"badge":"-17%","label_ru":"6 месяцев","label_en":"6 months"},
  {"id":"12m","days":365,"price_rub":330,"badge":"-32%","label_ru":"1 год","label_en":"1 year"}
]$json$::jsonb
where id = 'subscription_plans';
