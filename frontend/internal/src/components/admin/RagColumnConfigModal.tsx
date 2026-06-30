import { useState, useEffect } from 'react'
import { useQuery } from '@tanstack/react-query'
import { X, Settings } from 'lucide-react'
import { getSchema } from '../../api/admin/schema'
import { updateRagTableColumns, type RagTable, type ColumnConfig } from '../../api/admin/ragTables'

interface Props {
  dsId: number
  table: RagTable
  onSaved: () => void
  onClose: () => void
}

const CHUNKING_STRATEGIES = ['recursive', 'sentence', 'fixed']

export default function RagColumnConfigModal({ dsId, table, onSaved, onClose }: Props) {
  const { data: schemaTables = [] } = useQuery({
    queryKey: ['admin-schema', dsId],
    queryFn: () => getSchema(dsId),
  })

  const tableSchema = schemaTables.find(t => t.tableName === table.sourceTable)
  const columns = tableSchema?.columns ?? []
  const colNames = columns.map(c => c.name)

  const parseComma = (v: string | undefined) =>
    v ? v.split(',').filter(Boolean) : []

  const [pkColumn, setPkColumn] = useState(table.pkColumn || 'id')
  const [titleColumn, setTitleColumn] = useState(table.titleColumn || '')
  const [contentColumns, setContentColumns] = useState<string[]>(parseComma(table.contentColumnsJson))
  const [metadataColumns, setMetadataColumns] = useState<string[]>(parseComma(table.metadataColumnsJson))
  const [chunkingStrategy, setChunkingStrategy] = useState(table.chunkingStrategy || 'recursive')
  const [chunkSize, setChunkSize] = useState(table.chunkSize || 500)
  const [chunkOverlap, setChunkOverlap] = useState(table.chunkOverlap || 50)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')

  // 스키마 로드 후 아직 빈 컬럼 설정이면 자동 채우기
  useEffect(() => {
    if (columns.length === 0) return
    if (contentColumns.length === 0) {
      const textTypes = new Set(['varchar', 'text', 'mediumtext', 'longtext', 'char', 'tinytext'])
      const auto = columns
        .filter(c => !c.primaryKey && textTypes.has(c.dataType.toLowerCase()))
        .map(c => c.name)
      setContentColumns(auto)
    }
    if (!pkColumn || pkColumn === 'id') {
      const pk = columns.find(c => c.primaryKey)?.name ?? 'id'
      setPkColumn(pk)
    }
  }, [columns.length]) // eslint-disable-line

  const toggleMulti = (
    col: string,
    selected: string[],
    setSelected: (v: string[]) => void,
  ) => {
    setSelected(
      selected.includes(col) ? selected.filter(c => c !== col) : [...selected, col],
    )
  }

  const handleSave = async () => {
    setError('')
    if (contentColumns.length === 0) {
      setError('content 컬럼을 1개 이상 선택해야 합니다.')
      return
    }
    setSaving(true)
    try {
      const payload: ColumnConfig = {
        titleColumn: titleColumn || null,
        contentColumns,
        metadataColumns,
        pkColumn,
        chunkingStrategy,
        chunkSize,
        chunkOverlap,
      }
      await updateRagTableColumns(dsId, table.sourceTable, payload)
      onSaved()
      onClose()
    } catch (e) {
      setError('저장 실패: ' + String(e))
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
      <div className="bg-white rounded-xl shadow-2xl w-[620px] max-h-[85vh] flex flex-col">
        {/* Header */}
        <div className="flex items-center justify-between px-5 py-4 border-b border-gray-200">
          <div className="flex items-center gap-2.5">
            <Settings size={17} className="text-blue-600" />
            <div>
              <h2 className="text-sm font-semibold text-gray-900">
                컬럼 설정 —{' '}
                <span className="font-mono text-blue-700">{table.sourceTable}</span>
              </h2>
              <p className="text-xs text-gray-500 mt-0.5">임베딩·메타데이터 컬럼과 청킹 파라미터를 설정합니다.</p>
            </div>
          </div>
          <button onClick={onClose} className="text-gray-400 hover:text-gray-600">
            <X size={18} />
          </button>
        </div>

        {/* Body */}
        <div className="flex-1 overflow-y-auto px-5 py-4 space-y-5">
          {/* PK / Title */}
          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="block text-xs font-semibold text-gray-600 mb-1.5">PK 컬럼</label>
              <select
                value={pkColumn}
                onChange={e => setPkColumn(e.target.value)}
                className="w-full border border-gray-300 rounded-lg px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
              >
                {colNames.map(c => <option key={c} value={c}>{c}</option>)}
                {!colNames.includes(pkColumn) && <option value={pkColumn}>{pkColumn}</option>}
              </select>
            </div>
            <div>
              <label className="block text-xs font-semibold text-gray-600 mb-1.5">Title 컬럼 (선택)</label>
              <select
                value={titleColumn}
                onChange={e => setTitleColumn(e.target.value)}
                className="w-full border border-gray-300 rounded-lg px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
              >
                <option value="">— 없음 —</option>
                {colNames.map(c => <option key={c} value={c}>{c}</option>)}
              </select>
            </div>
          </div>

          {/* Content columns */}
          <div>
            <label className="block text-xs font-semibold text-gray-600 mb-1.5">
              Content 컬럼 <span className="text-red-500">*</span>
              <span className="ml-1 font-normal text-gray-400">(임베딩 대상, 복수 선택 가능)</span>
            </label>
            <ColumnCheckboxGroup
              colNames={colNames}
              selected={contentColumns}
              columns={columns}
              onToggle={col => toggleMulti(col, contentColumns, setContentColumns)}
            />
          </div>

          {/* Metadata columns */}
          <div>
            <label className="block text-xs font-semibold text-gray-600 mb-1.5">
              Metadata 컬럼
              <span className="ml-1 font-normal text-gray-400">(검색 결과에 포함, 복수 선택)</span>
            </label>
            <ColumnCheckboxGroup
              colNames={colNames}
              selected={metadataColumns}
              columns={columns}
              onToggle={col => toggleMulti(col, metadataColumns, setMetadataColumns)}
            />
          </div>

          {/* Chunking */}
          <div className="border-t border-gray-100 pt-4">
            <p className="text-xs font-semibold text-gray-600 mb-3">청킹 파라미터</p>
            <div className="grid grid-cols-3 gap-3">
              <div>
                <label className="block text-xs text-gray-500 mb-1">전략</label>
                <select
                  value={chunkingStrategy}
                  onChange={e => setChunkingStrategy(e.target.value)}
                  className="w-full border border-gray-300 rounded-lg px-2 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
                >
                  {CHUNKING_STRATEGIES.map(s => <option key={s} value={s}>{s}</option>)}
                </select>
              </div>
              <div>
                <label className="block text-xs text-gray-500 mb-1">청크 크기</label>
                <input
                  type="number"
                  min={100}
                  max={4000}
                  value={chunkSize}
                  onChange={e => setChunkSize(Number(e.target.value))}
                  className="w-full border border-gray-300 rounded-lg px-2 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
                />
              </div>
              <div>
                <label className="block text-xs text-gray-500 mb-1">오버랩</label>
                <input
                  type="number"
                  min={0}
                  max={500}
                  value={chunkOverlap}
                  onChange={e => setChunkOverlap(Number(e.target.value))}
                  className="w-full border border-gray-300 rounded-lg px-2 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
                />
              </div>
            </div>
          </div>

          {error && <p className="text-xs text-red-500">{error}</p>}
        </div>

        {/* Footer */}
        <div className="flex items-center justify-end gap-2 px-5 py-3.5 border-t border-gray-200 bg-gray-50 rounded-b-xl">
          <button
            onClick={onClose}
            className="px-4 py-1.5 text-sm text-gray-600 border border-gray-300 rounded-lg hover:bg-white"
          >
            취소
          </button>
          <button
            onClick={handleSave}
            disabled={saving}
            className="px-4 py-1.5 text-sm font-medium bg-blue-600 hover:bg-blue-700 disabled:opacity-50 text-white rounded-lg"
          >
            {saving ? '저장 중…' : '저장'}
          </button>
        </div>
      </div>
    </div>
  )
}

function ColumnCheckboxGroup({
  colNames,
  selected,
  columns,
  onToggle,
}: {
  colNames: string[]
  selected: string[]
  columns: { name: string; dataType: string; comment: string; primaryKey: boolean }[]
  onToggle: (col: string) => void
}) {
  const colMap = Object.fromEntries(columns.map(c => [c.name, c]))
  return (
    <div className="border border-gray-200 rounded-lg max-h-36 overflow-y-auto divide-y divide-gray-100">
      {colNames.length === 0 ? (
        <p className="px-3 py-2 text-xs text-gray-400">스키마 정보 없음</p>
      ) : (
        colNames.map(col => {
          const meta = colMap[col]
          return (
            <label key={col} className="flex items-center gap-2.5 px-3 py-1.5 hover:bg-gray-50 cursor-pointer">
              <input
                type="checkbox"
                checked={selected.includes(col)}
                onChange={() => onToggle(col)}
                className="rounded border-gray-300 text-blue-600 focus:ring-blue-500"
              />
              <span className="font-mono text-xs text-gray-800">{col}</span>
              {meta?.primaryKey && (
                <span className="text-xs text-yellow-600 font-bold">PK</span>
              )}
              <span className="text-xs text-gray-400">{meta?.dataType}</span>
              {meta?.comment && (
                <span className="text-xs text-gray-400 truncate">({meta.comment})</span>
              )}
            </label>
          )
        })
      )}
    </div>
  )
}
