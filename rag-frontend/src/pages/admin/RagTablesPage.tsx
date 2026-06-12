import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { Plus, RefreshCw, Trash2 } from 'lucide-react'
import { getRagTables, createRagTable, deleteRagTable, resyncRagTable } from '../../api/admin/ragTables'

export default function RagTablesPage() {
  const qc = useQueryClient()

  const { data: tables = [], isLoading, error } = useQuery({
    queryKey: ['admin-rag-tables'],
    queryFn: getRagTables,
  })

  const createMut = useMutation({
    mutationFn: createRagTable,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['admin-rag-tables'] })
      setShowForm(false)
      setForm({ tableName: '', description: '' })
    },
  })

  const deleteMut = useMutation({
    mutationFn: deleteRagTable,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['admin-rag-tables'] }),
  })

  const resyncMut = useMutation({ mutationFn: resyncRagTable })

  const [showForm, setShowForm] = useState(false)
  const [form, setForm] = useState({ tableName: '', description: '' })
  const [syncing, setSyncing] = useState<string | null>(null)

  const handleCreate = (e: React.FormEvent) => {
    e.preventDefault()
    createMut.mutate(form)
  }

  const handleResync = async (tableName: string) => {
    setSyncing(tableName)
    try {
      await resyncMut.mutateAsync(tableName)
    } finally {
      setSyncing(null)
    }
  }

  return (
    <div className="p-6">
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-xl font-semibold text-gray-900">RAG 테이블</h1>
          <p className="text-sm text-gray-500 mt-0.5">벡터 임베딩 대상 테이블을 관리합니다.</p>
        </div>
        <button
          onClick={() => setShowForm(v => !v)}
          className="flex items-center gap-2 bg-blue-600 hover:bg-blue-700 text-white px-3 py-2 rounded-lg text-sm font-medium"
        >
          <Plus size={15} /> 테이블 등록
        </button>
      </div>

      {showForm && (
        <form
          onSubmit={handleCreate}
          className="mb-6 bg-white border border-gray-200 rounded-xl p-4 flex flex-wrap gap-3 items-end"
        >
          <div>
            <label className="block text-xs font-medium text-gray-600 mb-1">테이블명</label>
            <input
              required
              value={form.tableName}
              onChange={e => setForm(p => ({ ...p, tableName: e.target.value }))}
              placeholder="schema.table_name"
              className="border border-gray-300 rounded-lg px-3 py-1.5 text-sm w-52 focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
          </div>
          <div>
            <label className="block text-xs font-medium text-gray-600 mb-1">설명 (선택)</label>
            <input
              value={form.description}
              onChange={e => setForm(p => ({ ...p, description: e.target.value }))}
              className="border border-gray-300 rounded-lg px-3 py-1.5 text-sm w-64 focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
          </div>
          <button
            type="submit"
            disabled={createMut.isPending}
            className="bg-blue-600 hover:bg-blue-700 disabled:opacity-50 text-white px-4 py-1.5 rounded-lg text-sm font-medium"
          >
            {createMut.isPending ? '등록 중…' : '등록'}
          </button>
          <button
            type="button"
            onClick={() => setShowForm(false)}
            className="text-gray-500 px-3 py-1.5 text-sm"
          >
            취소
          </button>
        </form>
      )}

      {isLoading && <p className="text-sm text-gray-400">불러오는 중…</p>}
      {error && <p className="text-sm text-red-500">오류: {String(error)}</p>}

      <div className="bg-white border border-gray-200 rounded-xl overflow-hidden">
        <table className="w-full text-sm">
          <thead className="bg-gray-50 border-b border-gray-200">
            <tr>
              {['테이블명', '설명', '상태', '등록일', ''].map(h => (
                <th
                  key={h}
                  className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wide"
                >
                  {h}
                </th>
              ))}
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {tables.map(t => (
              <tr key={t.id} className="hover:bg-gray-50">
                <td className="px-4 py-3 font-mono text-gray-900 text-xs">{t.tableName}</td>
                <td className="px-4 py-3 text-gray-600">{t.description || '-'}</td>
                <td className="px-4 py-3">
                  <span
                    className={`inline-flex items-center px-2 py-0.5 rounded text-xs font-medium ${
                      t.active ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-500'
                    }`}
                  >
                    {t.active ? '활성' : '비활성'}
                  </span>
                </td>
                <td className="px-4 py-3 text-gray-400 text-xs">
                  {new Date(t.createdAt).toLocaleDateString()}
                </td>
                <td className="px-4 py-3">
                  <div className="flex items-center gap-3">
                    <button
                      onClick={() => handleResync(t.tableName)}
                      disabled={syncing === t.tableName}
                      className="flex items-center gap-1 text-blue-500 hover:text-blue-700 text-xs disabled:opacity-50"
                    >
                      <RefreshCw size={12} className={syncing === t.tableName ? 'animate-spin' : ''} />
                      재동기화
                    </button>
                    <button
                      onClick={() => {
                        if (window.confirm(`${t.tableName}을 삭제하시겠습니까?`)) {
                          deleteMut.mutate(t.tableName)
                        }
                      }}
                      className="text-red-400 hover:text-red-600"
                    >
                      <Trash2 size={14} />
                    </button>
                  </div>
                </td>
              </tr>
            ))}
            {tables.length === 0 && !isLoading && (
              <tr>
                <td colSpan={5} className="px-4 py-8 text-center text-gray-400 text-sm">
                  등록된 테이블이 없습니다.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  )
}
