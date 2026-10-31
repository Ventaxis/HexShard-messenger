-- ====================================================================
-- Migration: HexShard Virtual Numbers & Profile Storage RPC Fix
-- Date: 2026-09-29
-- ====================================================================

-- Создание расширения для генерации UUID при необходимости
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- Таблица учета виртуальных номеров HexShard
CREATE TABLE IF NOT EXISTS public.hex_numbers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    number VARCHAR(16) NOT NULL UNIQUE,
    raw_number VARCHAR(8) NOT NULL UNIQUE,
    owner_id UUID REFERENCES auth.users(id) ON DELETE SET NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'available', -- 'available', 'reserved', 'active'
    reserved_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- Убедимся, что все колонки существуют на случай существующей таблицы
ALTER TABLE public.hex_numbers ADD COLUMN IF NOT EXISTS id UUID DEFAULT gen_random_uuid();
ALTER TABLE public.hex_numbers ADD COLUMN IF NOT EXISTS raw_number VARCHAR(8);
ALTER TABLE public.hex_numbers ADD COLUMN IF NOT EXISTS owner_id UUID REFERENCES auth.users(id) ON DELETE SET NULL;
ALTER TABLE public.hex_numbers ADD COLUMN IF NOT EXISTS status VARCHAR(20) DEFAULT 'available';
ALTER TABLE public.hex_numbers ADD COLUMN IF NOT EXISTS reserved_at TIMESTAMPTZ;
ALTER TABLE public.hex_numbers ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ;

-- Обновим raw_number если он пуст
UPDATE public.hex_numbers 
SET raw_number = regexp_replace(number, '\D', '', 'g')
WHERE raw_number IS NULL AND number IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_hex_numbers_owner ON public.hex_numbers(owner_id);
CREATE INDEX IF NOT EXISTS idx_hex_numbers_status ON public.hex_numbers(status);
CREATE INDEX IF NOT EXISTS idx_hex_numbers_raw ON public.hex_numbers(raw_number);

-- Убедимся, что таблица profiles содержит необходимые поля
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS bio TEXT DEFAULT '';
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS date_of_birth TEXT DEFAULT '';
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS avatar_url TEXT DEFAULT '';
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS hex_number VARCHAR(24) DEFAULT '';

-- Удаление старых конфликтующих сигнатур
DROP FUNCTION IF EXISTS public.reserve_hex_number(TEXT);
DROP FUNCTION IF EXISTS public.reserve_hex_number();

-- RPC: Резервирование номера (с поддержкой p_preferred со значением DEFAULT NULL)
CREATE OR REPLACE FUNCTION public.reserve_hex_number(p_preferred TEXT DEFAULT NULL)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, auth
AS $$
DECLARE
    v_user_id UUID := auth.uid();
    v_record RECORD;
    v_raw VARCHAR(8);
    v_clean_pref VARCHAR(8);
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Unauthorized' USING ERRCODE = '42501';
    END IF;

    -- Очистка предпочтительного номера
    IF p_preferred IS NOT NULL AND length(trim(p_preferred)) > 0 THEN
        v_clean_pref := regexp_replace(p_preferred, '\D', '', 'g');
    ELSE
        v_clean_pref := NULL;
    END IF;

    -- Попытка зарезервировать предпочтительный номер
    IF v_clean_pref IS NOT NULL AND length(v_clean_pref) = 8 THEN
        SELECT * INTO v_record
        FROM public.hex_numbers
        WHERE raw_number = v_clean_pref 
          AND (status = 'available' OR (status = 'reserved' AND expires_at < NOW()) OR owner_id = v_user_id)
        FOR UPDATE SKIP LOCKED
        LIMIT 1;
    END IF;

    -- Если не найден предпочтительный, берем любой доступный или генерируем
    IF v_record IS NULL THEN
        SELECT * INTO v_record
        FROM public.hex_numbers
        WHERE status = 'available' OR (status = 'reserved' AND expires_at < NOW())
        ORDER BY RANDOM()
        FOR UPDATE SKIP LOCKED
        LIMIT 1;
    END IF;

    -- Если свободных записей в пуле нет, генерируем случайный 8-значный номер
    IF v_record IS NULL THEN
        LOOP
            v_raw := LPAD(FLOOR(RANDOM() * 100000000)::TEXT, 8, '0');
            BEGIN
                INSERT INTO public.hex_numbers (number, raw_number, owner_id, status, reserved_at, expires_at)
                VALUES ('+999 ' || SUBSTRING(v_raw FROM 1 FOR 4) || ' ' || SUBSTRING(v_raw FROM 5 FOR 4),
                        v_raw, v_user_id, 'reserved', NOW(), NOW() + INTERVAL '10 minutes')
                RETURNING * INTO v_record;
                EXIT;
            EXCEPTION WHEN unique_violation THEN
                -- Коллизия номера, повторяем цикл
            END;
        END LOOP;
    ELSE
        UPDATE public.hex_numbers
        SET owner_id = v_user_id,
            status = 'reserved',
            reserved_at = NOW(),
            expires_at = NOW() + INTERVAL '10 minutes'
        WHERE id = v_record.id
        RETURNING * INTO v_record;
    END IF;

    RETURN jsonb_build_object(
        'raw_number', v_record.raw_number,
        'number', v_record.number,
        'formatted', v_record.number,
        'expires_at', EXTRACT(EPOCH FROM v_record.expires_at) * 1000
    );
END;
$$;

-- Перегрузка без аргументов для совместимости с PostgREST
CREATE OR REPLACE FUNCTION public.reserve_hex_number()
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
BEGIN
    RETURN public.reserve_hex_number(NULL);
END;
$$;

-- RPC: Подтверждение зарезервированного номера
CREATE OR REPLACE FUNCTION public.confirm_hex_number(p_raw_number TEXT)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, auth
AS $$
DECLARE
    v_user_id UUID := auth.uid();
    v_clean VARCHAR(8) := regexp_replace(p_raw_number, '\D', '', 'g');
    v_record RECORD;
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Unauthorized' USING ERRCODE = '42501';
    END IF;

    IF length(v_clean) != 8 THEN
        RAISE EXCEPTION 'Invalid number format' USING ERRCODE = '22023';
    END IF;

    UPDATE public.hex_numbers
    SET status = 'active',
        owner_id = v_user_id,
        expires_at = NULL
    WHERE raw_number = v_clean AND (owner_id = v_user_id OR status = 'reserved')
    RETURNING * INTO v_record;

    IF v_record IS NULL THEN
        RAISE EXCEPTION 'Number reservation not found or expired' USING ERRCODE = 'P0002';
    END IF;

    -- Обновляем профиль пользователя
    UPDATE public.profiles
    SET hex_number = v_record.number
    WHERE id = v_user_id;

    RETURN jsonb_build_object(
        'confirmed', true,
        'raw_number', v_record.raw_number,
        'formatted', v_record.number
    );
END;
$$;

-- Сброс кэша схемы PostgREST
NOTIFY pgrst, 'reload schema';
