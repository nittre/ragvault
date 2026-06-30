import { useState, useEffect } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { getSearchConfig, updateSearchConfig } from '../../api/admin/search'
import type { SearchConfigItem } from '../../api/admin/search'

function errorMessage(err: unknown): string {
  if (err instanceof Error) return err.message
  return '알 수 없는 오류가 발생했습니다.'
}

interface ConfigCardProps {
  item: SearchConfigItem
  onSaved: () => void
}

function TopKCard({ item, onSaved }: ConfigCardProps) {
  const [value, setValue] = useState(item.value)
  const [errorMsg, setErrorMsg] = useState<string | null>(null)

  useEffect(() => { setValue(item.value) }, [item.value])

  const mut = useMutation({
    mutationFn: () => updateSearchConfig(item.key, value),
    onSuccess: onSaved,
    onError: (err: unknown) => setErrorMsg(errorMessage(err)),
  })

  return (
    <div className="bg-white border border-gray-200 rounded-xl p-5">
      <div className="flex items-start justify-between mb-1">
        <label className="text-sm font-medium text-gray-900">검색 결과 수 (top_k)</label>
        <span className="text-xs text-gray-400 font-mono">{item.key}</span>
      </div>
      {item.description && (
        <p className="text-xs text-gray-400 mb-3">{item.description}</p>
      )}
      <div className="flex items-center gap-3">
        <input
          type="number"
          min={1}
          max={20}
          value={value}
          onChange={e => { setValue(e.target.value); setErrorMsg(null) }}
          className="border border-gray-300 rounded-lg px-3 py-1.5 text-sm w-24 focus:outline-none focus:ring-2 focus:ring-blue-500"
        />
        <button
          onClick={() => mut.mutate()}
          disabled={mut.isPending}
          className="bg-blue-600 hover:bg-blue-700 disabled:opacity-50 text-white px-3 py-1.5 rounded-lg text-sm font-medium transition-colors"
        >
          {mut.isPending ? '저장 중…' : '저장'}
        </button>
      </div>
      {errorMsg && <p className="mt-2 text-xs text-red-500">오류: {errorMsg}</p>}
      {mut.isSuccess && <p className="mt-2 text-xs text-green-600">저장되었습니다.</p>}
    </div>
  )
}

function ThresholdCard({ item, onSaved }: ConfigCardProps) {
  const [value, setValue] = useState(item.value)
  const [errorMsg, setErrorMsg] = useState<string | null>(null)

  useEffect(() => { setValue(item.value) }, [item.value])

  const mut = useMutation({
    mutationFn: () => updateSearchConfig(item.key, value),
    onSuccess: onSaved,
    onError: (err: unknown) => setErrorMsg(errorMessage(err)),
  })

  const numValue = parseFloat(value) || 0

  return (
    <div className="bg-white border border-gray-200 rounded-xl p-5">
      <div className="flex items-start justify-between mb-1">
        <label className="text-sm font-medium text-gray-900">유사도 임계값 (threshold)</label>
        <span className="text-xs text-gray-400 font-mono">{item.key}</span>
      </div>
      {item.description && (
        <p className="text-xs text-gray-400 mb-3">{item.description}</p>
      )}
      <div className="flex items-center gap-3 mb-3">
        <input
          type="range"
          min={0}
          max={1}
          step={0.01}
          value={numValue}
          onChange={e => { setValue(e.target.value); setErrorMsg(null) }}
          className="flex-1 accent-blue-600"
        />
        <input
          type="number"
          min={0}
          max={1}
          step={0.01}
          value={value}
          onChange={e => { setValue(e.target.value); setErrorMsg(null) }}
          className="border border-gray-300 rounded-lg px-3 py-1.5 text-sm w-20 focus:outline-none focus:ring-2 focus:ring-blue-500"
        />
      </div>
      <button
        onClick={() => mut.mutate()}
        disabled={mut.isPending}
        className="bg-blue-600 hover:bg-blue-700 disabled:opacity-50 text-white px-3 py-1.5 rounded-lg text-sm font-medium transition-colors"
      >
        {mut.isPending ? '저장 중…' : '저장'}
      </button>
      {errorMsg && <p className="mt-2 text-xs text-red-500">오류: {errorMsg}</p>}
      {mut.isSuccess && <p className="mt-2 text-xs text-green-600">저장되었습니다.</p>}
    </div>
  )
}

function TextareaCard({ item, label, onSaved }: ConfigCardProps & { label: string }) {
  const [value, setValue] = useState(item.value)
  const [errorMsg, setErrorMsg] = useState<string | null>(null)

  useEffect(() => { setValue(item.value) }, [item.value])

  const mut = useMutation({
    mutationFn: () => updateSearchConfig(item.key, value),
    onSuccess: onSaved,
    onError: (err: unknown) => setErrorMsg(errorMessage(err)),
  })

  return (
    <div className="bg-white border border-gray-200 rounded-xl p-5">
      <div className="flex items-start justify-between mb-1">
        <label className="text-sm font-medium text-gray-900">{label}</label>
        <span className="text-xs text-gray-400 font-mono">{item.key}</span>
      </div>
      {item.description && (
        <p className="text-xs text-gray-400 mb-3">{item.description}</p>
      )}
      <textarea
        rows={3}
        value={value}
        onChange={e => { setValue(e.target.value); setErrorMsg(null) }}
        className="border border-gray-300 rounded-lg px-3 py-2 text-sm w-full resize-y focus:outline-none focus:ring-2 focus:ring-blue-500 leading-relaxed mb-3"
      />
      <button
        onClick={() => mut.mutate()}
        disabled={mut.isPending}
        className="bg-blue-600 hover:bg-blue-700 disabled:opacity-50 text-white px-3 py-1.5 rounded-lg text-sm font-medium transition-colors"
      >
        {mut.isPending ? '저장 중…' : '저장'}
      </button>
      {errorMsg && <p className="mt-2 text-xs text-red-500">오류: {errorMsg}</p>}
      {mut.isSuccess && <p className="mt-2 text-xs text-green-600">저장되었습니다.</p>}
    </div>
  )
}

function SqlEnabledCard({ item, onSaved }: { item?: SearchConfigItem; onSaved: () => void }) {
  const enabled = (item?.value ?? 'false') === 'true'
  const [errorMsg, setErrorMsg] = useState<string | null>(null)

  const mut = useMutation({
    mutationFn: (next: boolean) => updateSearchConfig('sql_enabled', String(next)),
    onSuccess: onSaved,
    onError: (err: unknown) => setErrorMsg(errorMessage(err)),
  })

  return (
    <div className="bg-white border border-gray-200 rounded-xl p-5">
      <div className="flex items-start justify-between mb-1">
        <label className="text-sm font-medium text-gray-900">위젯 SQL 조회 허용 (text-to-sql)</label>
        <span className="text-xs text-gray-400 font-mono">sql_enabled</span>
      </div>
      <p className="text-xs text-gray-400 mb-3">
        공개 위젯 채팅에서 자연어→SQL 조회 경로를 허용합니다. 기본값은 비활성이며, 본인 인증 흐름이 없는
        상태에서는 임의 데이터 조회 위험이 있으므로 내부 검증·데모 용도로만 사용하세요.
      </p>
      <button
        type="button"
        role="switch"
        aria-checked={enabled}
        disabled={mut.isPending}
        onClick={() => { setErrorMsg(null); mut.mutate(!enabled) }}
        className={`relative inline-flex h-6 w-11 items-center rounded-full transition-colors disabled:opacity-50 ${
          enabled ? 'bg-blue-600' : 'bg-gray-300'
        }`}
      >
        <span
          className={`inline-block h-4 w-4 transform rounded-full bg-white transition-transform ${
            enabled ? 'translate-x-6' : 'translate-x-1'
          }`}
        />
      </button>
      <span className="ml-3 text-sm text-gray-600">{enabled ? '활성' : '비활성'}</span>
      {errorMsg && <p className="mt-2 text-xs text-red-500">오류: {errorMsg}</p>}
    </div>
  )
}

const CARD_LABELS: Record<string, string> = {
  no_results_response: '결과 없음 응답 메시지',
  injection_blocked_response: '주입 차단 응답 메시지',
}

const KNOWN_KEYS = ['top_k', 'threshold', 'no_results_response', 'injection_blocked_response', 'sql_enabled']

export default function SearchConfigPage() {
  const qc = useQueryClient()

  const { data: items = [], isLoading, error } = useQuery({
    queryKey: ['admin-search-config'],
    queryFn: getSearchConfig,
  })

  function handleSaved() {
    qc.invalidateQueries({ queryKey: ['admin-search-config'] })
  }

  const byKey = Object.fromEntries(items.map(i => [i.key, i]))

  return (
    <div className="p-6">
      <div className="mb-6">
        <h1 className="text-xl font-semibold text-gray-900">검색 설정</h1>
      </div>

      {isLoading && <p className="text-sm text-gray-400">불러오는 중…</p>}
      {error && <p className="text-sm text-red-500">오류: {String(error)}</p>}

      {!isLoading && (
        <div className="flex flex-col gap-4 max-w-2xl">
          {byKey['top_k'] && (
            <TopKCard item={byKey['top_k']} onSaved={handleSaved} />
          )}
          {byKey['threshold'] && (
            <ThresholdCard item={byKey['threshold']} onSaved={handleSaved} />
          )}
          {['no_results_response', 'injection_blocked_response'].map(key =>
            byKey[key] ? (
              <TextareaCard
                key={key}
                item={byKey[key]}
                label={CARD_LABELS[key] ?? key}
                onSaved={handleSaved}
              />
            ) : null
          )}
          <SqlEnabledCard item={byKey['sql_enabled']} onSaved={handleSaved} />
          {/* 나머지 알 수 없는 키 */}
          {items
            .filter(i => !KNOWN_KEYS.includes(i.key))
            .map(item => (
              <TextareaCard
                key={item.key}
                item={item}
                label={item.key}
                onSaved={handleSaved}
              />
            ))}
        </div>
      )}
    </div>
  )
}
