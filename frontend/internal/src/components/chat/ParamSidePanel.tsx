import React from 'react'
import { useQuery } from '@tanstack/react-query'
import { X } from 'lucide-react'
import { useParamStore } from '../../stores/paramStore'
import { getParamProfile, type ParamLimitInfo } from '../../api/paramProfile'
import type { ForcePath, HybridStyle } from '../../types'

/** limits 맵에서 한도를 꺼내되, 아직 로딩 전이거나 admin_param_limits에 row가 없으면 하드코딩 fallback 사용. */
function resolveRange(
  limits: Record<string, ParamLimitInfo> | undefined,
  key: string,
  fallbackMin: number,
  fallbackMax: number
): { min: number; max: number } {
  const limit = limits?.[key]
  return {
    min: limit?.minValue ?? fallbackMin,
    max: limit?.maxValue ?? fallbackMax,
  }
}

export default function ParamSidePanel() {
  const { params, systemPrompt, setParams, setSystemPrompt, togglePanel, resetParams } =
    useParamStore()

  const { data: profile } = useQuery({
    queryKey: ['user-param-profile'],
    queryFn: getParamProfile,
  })
  const limits = profile?.limits

  const topK = resolveRange(limits, 'top_k', 1, 20)
  const threshold = resolveRange(limits, 'similarity_threshold', 0, 1)
  const temperature = resolveRange(limits, 'temperature', 0, 2)
  const topP = resolveRange(limits, 'top_p', 0, 1)
  const maxTokens = resolveRange(limits, 'max_tokens', 100, 4096)
  const queryTimeoutSec = resolveRange(limits, 'query_timeout_sec', 5, 60)
  const maxResultRows = resolveRange(limits, 'max_result_rows', 10, 10000)
  const maxHistoryTurns = resolveRange(limits, 'max_history_turns', 1, 50)

  const sqlTemperature = limits?.sql_temperature
  const sqlFewShotExamples = limits?.sql_few_shot_examples
  const maxContextTokens = limits?.max_context_tokens

  return (
    <aside className="w-80 bg-white border-l border-gray-200 flex flex-col overflow-hidden">
      {/* 헤더 */}
      <div className="flex items-center justify-between px-4 py-3 border-b border-gray-200">
        <h2 className="font-semibold text-gray-800 text-sm">파라미터 설정</h2>
        <button onClick={togglePanel} className="text-gray-400 hover:text-gray-700 p-1 rounded">
          <X size={16} />
        </button>
      </div>

      <div className="flex-1 overflow-y-auto px-4 py-4 space-y-5 text-sm">
        {/* 시스템 프롬프트 */}
        <div>
          <label className="block font-medium text-gray-700 mb-1">시스템 프롬프트</label>
          <textarea
            value={systemPrompt}
            onChange={e => setSystemPrompt(e.target.value)}
            rows={4}
            className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500 resize-none"
            placeholder="시스템 지침을 입력하세요..."
          />
        </div>

        {/* Top-K */}
        <TunableSlider
          label="Top-K"
          value={params.topK ?? 5}
          limit={limits?.top_k}
          min={topK.min}
          max={topK.max}
          step={1}
          onChange={v => setParams({ topK: v })}
        />

        {/* 유사도 임계값 */}
        <TunableSlider
          label="유사도 임계값"
          value={params.threshold ?? 0.5}
          limit={limits?.similarity_threshold}
          min={threshold.min}
          max={threshold.max}
          step={0.01}
          decimals={2}
          onChange={v => setParams({ threshold: v })}
        />

        {/* Temperature */}
        <TunableSlider
          label="Temperature"
          value={params.temperature ?? 0.7}
          limit={limits?.temperature}
          min={temperature.min}
          max={temperature.max}
          step={0.01}
          decimals={2}
          onChange={v => setParams({ temperature: v })}
        />

        {/* Top P */}
        <TunableSlider
          label="Top P"
          value={params.topP ?? 0.9}
          limit={limits?.top_p}
          min={topP.min}
          max={topP.max}
          step={0.01}
          decimals={2}
          onChange={v => setParams({ topP: v })}
        />

        {/* 최대 토큰 */}
        <TunableSlider
          label="최대 토큰"
          value={params.maxTokens ?? 2048}
          limit={limits?.max_tokens}
          min={maxTokens.min}
          max={maxTokens.max}
          step={1}
          onChange={v => setParams({ maxTokens: v })}
        />

        {/* 쿼리 타임아웃 */}
        <TunableSlider
          label="쿼리 타임아웃(초)"
          value={params.queryTimeoutSec ?? 30}
          limit={limits?.query_timeout_sec}
          min={queryTimeoutSec.min}
          max={queryTimeoutSec.max}
          step={1}
          onChange={v => setParams({ queryTimeoutSec: v })}
        />

        {/* 최대 결과 행수 */}
        <TunableSlider
          label="최대 결과 행수"
          value={params.maxResultRows ?? 100}
          limit={limits?.max_result_rows}
          min={maxResultRows.min}
          max={maxResultRows.max}
          step={1}
          onChange={v => setParams({ maxResultRows: v })}
        />

        {/* 라우팅 강제 */}
        <div>
          <label className="block font-medium text-gray-700 mb-1">라우팅 강제</label>
          <select
            value={params.forcePath ?? 'AUTO'}
            onChange={e => setParams({ forcePath: e.target.value as ForcePath })}
            className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
          >
            <option value="AUTO">AUTO</option>
            <option value="FORCE_RAG">FORCE_RAG</option>
            <option value="FORCE_SQL">FORCE_SQL</option>
            <option value="FORCE_HYBRID">FORCE_HYBRID</option>
          </select>
        </div>

        {/* 하이브리드 스타일 */}
        <div>
          <label className="block font-medium text-gray-700 mb-1">하이브리드 스타일</label>
          <select
            value={params.hybridStyle ?? 'BALANCED'}
            onChange={e => setParams({ hybridStyle: e.target.value as HybridStyle })}
            className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
          >
            <option value="BALANCED">BALANCED</option>
            <option value="SQL_FIRST">SQL_FIRST</option>
            <option value="RAG_FIRST">RAG_FIRST</option>
          </select>
        </div>

        {/* 최대 히스토리 턴수 */}
        <TunableSlider
          label="최대 히스토리 턴수"
          value={params.maxHistoryTurns ?? 10}
          limit={limits?.max_history_turns}
          min={maxHistoryTurns.min}
          max={maxHistoryTurns.max}
          step={1}
          onChange={v => setParams({ maxHistoryTurns: v })}
        />

        {/* 잠금 파라미터 — 어드민이 설정한 실제 고정값 표시 */}
        <LockedField
          label="SQL Temperature 🔒"
          value={sqlTemperature?.fixedValue != null ? String(sqlTemperature.fixedValue) : '-'}
        />
        <LockedField
          label="Few-Shot 예시 수 🔒"
          value={sqlFewShotExamples?.fixedValue != null ? String(sqlFewShotExamples.fixedValue) : '-'}
        />
        <LockedField
          label="최대 컨텍스트 토큰 🔒"
          value={maxContextTokens?.fixedValue != null ? String(maxContextTokens.fixedValue) : '-'}
        />
      </div>

      {/* 초기화 버튼 */}
      <div className="px-4 py-3 border-t border-gray-200">
        <button
          onClick={resetParams}
          className="w-full border border-gray-300 hover:bg-gray-50 text-gray-700 rounded-lg px-4 py-2 text-sm font-medium transition-colors"
        >
          초기화
        </button>
      </div>
    </aside>
  )
}

/* 슬라이더 필드 */
interface SliderFieldProps {
  label: string
  value: number
  min: number
  max: number
  step: number
  decimals?: number
  onChange: (v: number) => void
}

/**
 * 관리자가 Guard B(잠금)로 설정했으면 편집 불가능한 LockedField로, 아니면 SliderField로 렌더링.
 * "유사도 임계값" 등 8개 파라미터는 이 컴포넌트를 통해서만 노출해야 잠금이 UI에 정확히 반영된다.
 */
interface TunableSliderProps extends SliderFieldProps {
  limit: ParamLimitInfo | undefined
}

function TunableSlider({ label, value, min, max, step, decimals, onChange, limit }: TunableSliderProps) {
  if (limit?.locked) {
    return (
      <LockedField
        label={`${label} 🔒`}
        value={limit.fixedValue != null ? String(limit.fixedValue) : '-'}
      />
    )
  }
  return (
    <SliderField
      label={label}
      value={value}
      min={min}
      max={max}
      step={step}
      decimals={decimals}
      onChange={onChange}
    />
  )
}

function SliderField({ label, value, min, max, step, decimals = 0, onChange }: SliderFieldProps) {
  const display = decimals > 0 ? value.toFixed(decimals) : String(value)
  return (
    <div>
      <div className="flex justify-between mb-1">
        <label className="font-medium text-gray-700">{label}</label>
        <span className="text-gray-500">{display}</span>
      </div>
      <input
        type="range"
        min={min}
        max={max}
        step={step}
        value={value}
        onChange={e => onChange(parseFloat(e.target.value))}
        className="w-full accent-blue-600"
      />
    </div>
  )
}

/* 잠금 필드 */
function LockedField({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <label className="block font-medium text-gray-400 mb-1">{label}</label>
      <input
        type="text"
        value={value}
        disabled
        className="w-full border border-gray-200 rounded-lg px-3 py-2 text-sm bg-gray-50 text-gray-400 cursor-not-allowed"
      />
    </div>
  )
}
