import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { Plus, Copy, RefreshCw, Trash2, Eye, EyeOff } from 'lucide-react'
import { getApiKeys, createApiKey, deleteApiKey, rotateApiKey } from '../../api/admin/apiKeys'

export default function ApiKeysPage() {
  const qc = useQueryClient()

  const { data: keys = [], isLoading, error } = useQuery({
    queryKey: ['admin-api-keys'],
    queryFn: getApiKeys,
  })

  const createMut = useMutation({
    mutationFn: createApiKey,
    onSuccess: data => {
      qc.invalidateQueries({ queryKey: ['admin-api-keys'] })
      setNewKey(data.rawKey ?? null)
      setShowForm(false)
      setForm({ name: '', scopes: 'chat', expiresAt: '' })
    },
  })

  const deleteMut = useMutation({
    mutationFn: deleteApiKey,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['admin-api-keys'] }),
  })

  const rotateMut = useMutation({
    mutationFn: rotateApiKey,
    onSuccess: data => {
      qc.invalidateQueries({ queryKey: ['admin-api-keys'] })
      setNewKey(data.rawKey ?? null)
    },
  })

  const [showForm, setShowForm] = useState(false)
  const [form, setForm] = useState({ name: '', scopes: 'chat', expiresAt: '' })
  const [newKey, setNewKey] = useState<string | null>(null)
  const [showKey, setShowKey] = useState(false)
  const [copied, setCopied] = useState(false)

  const handleCreate = (e: React.FormEvent) => {
    e.preventDefault()
    createMut.mutate(form)
  }

  const handleCopy = () => {
    if (!newKey) return
    navigator.clipboard.writeText(newKey)
    setCopied(true)
    setTimeout(() => setCopied(false), 2000)
  }

  return (
    <div className="p-6">
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-xl font-semibold text-gray-900">API 키</h1>
          <p className="text-sm text-gray-500 mt-0.5">외부 API 클라이언트용 인증 키를 관리합니다.</p>
        </div>
        <button
          onClick={() => setShowForm(v => !v)}
          className="flex items-center gap-2 bg-blue-600 hover:bg-blue-700 text-white px-3 py-2 rounded-lg text-sm font-medium"
        >
          <Plus size={15} /> 키 발급
        </button>
      </div>

      {/* 새 키 발급 결과 배너 */}
      {newKey && (
        <div className="mb-6 bg-yellow-50 border border-yellow-200 rounded-xl p-4">
          <p className="text-sm font-medium text-yellow-800 mb-2">
            ⚠️ 새 API 키가 발급되었습니다. 이 키는 지금만 표시됩니다 — 반드시 복사하세요.
          </p>
          <div className="flex items-center gap-2">
            <code className="flex-1 bg-white border border-yellow-300 rounded px-3 py-2 text-sm font-mono text-gray-900 overflow-x-auto">
              {showKey ? newKey : '•'.repeat(Math.min(newKey.length, 48))}
            </code>
            <button
              onClick={() => setShowKey(v => !v)}
              className="p-2 text-yellow-700 hover:text-yellow-900 flex-shrink-0"
              title={showKey ? '숨기기' : '보기'}
            >
              {showKey ? <EyeOff size={16} /> : <Eye size={16} />}
            </button>
            <button
              onClick={handleCopy}
              className="p-2 text-yellow-700 hover:text-yellow-900 flex-shrink-0"
              title="복사"
            >
              <Copy size={16} />
            </button>
          </div>
          {copied && <p className="text-xs text-yellow-700 mt-1">클립보드에 복사됐습니다.</p>}
          <button
            onClick={() => { setNewKey(null); setShowKey(false) }}
            className="text-xs text-yellow-600 hover:text-yellow-800 mt-2"
          >
            닫기
          </button>
        </div>
      )}

      {showForm && (
        <form
          onSubmit={handleCreate}
          className="mb-6 bg-white border border-gray-200 rounded-xl p-4 flex flex-wrap gap-3 items-end"
        >
          <div>
            <label className="block text-xs font-medium text-gray-600 mb-1">키 이름</label>
            <input
              required
              value={form.name}
              onChange={e => setForm(p => ({ ...p, name: e.target.value }))}
              placeholder="예: 외부-앱-v1"
              className="border border-gray-300 rounded-lg px-3 py-1.5 text-sm w-44 focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
          </div>
          <div>
            <label className="block text-xs font-medium text-gray-600 mb-1">스코프</label>
            <input
              value={form.scopes}
              onChange={e => setForm(p => ({ ...p, scopes: e.target.value }))}
              placeholder="chat,admin"
              className="border border-gray-300 rounded-lg px-3 py-1.5 text-sm w-32 focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
          </div>
          <div>
            <label className="block text-xs font-medium text-gray-600 mb-1">만료일 (선택)</label>
            <input
              type="date"
              value={form.expiresAt}
              onChange={e => setForm(p => ({ ...p, expiresAt: e.target.value }))}
              className="border border-gray-300 rounded-lg px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
          </div>
          <button
            type="submit"
            disabled={createMut.isPending}
            className="bg-blue-600 hover:bg-blue-700 disabled:opacity-50 text-white px-4 py-1.5 rounded-lg text-sm font-medium"
          >
            {createMut.isPending ? '발급 중…' : '발급'}
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
              {['이름', '키 접두사', '스코프', '만료일', '최근 사용', '상태', ''].map(h => (
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
            {keys.map(k => (
              <tr key={k.id} className="hover:bg-gray-50">
                <td className="px-4 py-3 font-medium text-gray-900">{k.name}</td>
                <td className="px-4 py-3 font-mono text-gray-500 text-xs">{k.keyPrefix}…</td>
                <td className="px-4 py-3 text-gray-600 text-xs">{k.scopes}</td>
                <td className="px-4 py-3 text-gray-400 text-xs">
                  {k.expiresAt ? new Date(k.expiresAt).toLocaleDateString() : '무기한'}
                </td>
                <td className="px-4 py-3 text-gray-400 text-xs">
                  {k.lastUsedAt ? new Date(k.lastUsedAt).toLocaleDateString() : '-'}
                </td>
                <td className="px-4 py-3">
                  <span
                    className={`inline-flex items-center px-2 py-0.5 rounded text-xs font-medium ${
                      k.active ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-500'
                    }`}
                  >
                    {k.active ? '활성' : '폐기됨'}
                  </span>
                </td>
                <td className="px-4 py-3">
                  <div className="flex items-center gap-3">
                    <button
                      onClick={() => {
                        if (window.confirm(`${k.name} 키를 교체하시겠습니까? 기존 키는 즉시 폐기됩니다.`)) {
                          rotateMut.mutate(k.id)
                        }
                      }}
                      disabled={rotateMut.isPending}
                      className="flex items-center gap-1 text-blue-500 hover:text-blue-700 text-xs disabled:opacity-50"
                    >
                      <RefreshCw size={12} /> 교체
                    </button>
                    <button
                      onClick={() => {
                        if (window.confirm(`${k.name} 키를 폐기하시겠습니까?`)) {
                          deleteMut.mutate(k.id)
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
            {keys.length === 0 && !isLoading && (
              <tr>
                <td colSpan={7} className="px-4 py-8 text-center text-gray-400 text-sm">
                  발급된 API 키가 없습니다.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  )
}
