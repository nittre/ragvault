import React from 'react'
import { X } from 'lucide-react'
import { useParamStore } from '../../stores/paramStore'
import type { ForcePath, HybridStyle } from '../../types'

export default function ParamSidePanel() {
  const { params, systemPrompt, setParams, setSystemPrompt, togglePanel, resetParams } =
    useParamStore()

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
        <SliderField
          label="Top-K"
          value={params.topK ?? 5}
          min={1}
          max={20}
          step={1}
          onChange={v => setParams({ topK: v })}
        />

        {/* 유사도 임계값 */}
        <SliderField
          label="유사도 임계값"
          value={params.threshold ?? 0.5}
          min={0}
          max={1}
          step={0.01}
          decimals={2}
          onChange={v => setParams({ threshold: v })}
        />

        {/* Temperature */}
        <SliderField
          label="Temperature"
          value={params.temperature ?? 0.7}
          min={0}
          max={2}
          step={0.01}
          decimals={2}
          onChange={v => setParams({ temperature: v })}
        />

        {/* Top P */}
        <SliderField
          label="Top P"
          value={params.topP ?? 0.9}
          min={0}
          max={1}
          step={0.01}
          decimals={2}
          onChange={v => setParams({ topP: v })}
        />

        {/* 최대 토큰 */}
        <SliderField
          label="최대 토큰"
          value={params.maxTokens ?? 2048}
          min={100}
          max={4096}
          step={1}
          onChange={v => setParams({ maxTokens: v })}
        />

        {/* 쿼리 타임아웃 */}
        <SliderField
          label="쿼리 타임아웃(초)"
          value={params.queryTimeoutSec ?? 30}
          min={5}
          max={60}
          step={1}
          onChange={v => setParams({ queryTimeoutSec: v })}
        />

        {/* 최대 결과 행수 */}
        <SliderField
          label="최대 결과 행수"
          value={params.maxResultRows ?? 100}
          min={10}
          max={10000}
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
        <SliderField
          label="최대 히스토리 턴수"
          value={params.maxHistoryTurns ?? 10}
          min={1}
          max={50}
          step={1}
          onChange={v => setParams({ maxHistoryTurns: v })}
        />

        {/* 잠금 파라미터 */}
        <LockedField label="SQL Temperature 🔒" value="0.1" />
        <LockedField label="Few-Shot 예시 수 🔒" value="5" />
        <LockedField label="최대 컨텍스트 토큰 🔒" value="5000" />
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
