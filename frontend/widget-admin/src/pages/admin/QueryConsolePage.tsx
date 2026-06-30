import { useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { Terminal, Send } from 'lucide-react'
import { runAdminQuery, type QueryResult } from '../../api/admin/query'

export default function QueryConsolePage() {
  const [message, setMessage] = useState('')
  const [result, setResult] = useState<QueryResult | null>(null)
  const [errorMsg, setErrorMsg] = useState<string | null>(null)

  const queryMut = useMutation({
    mutationFn: (msg: string) => runAdminQuery(msg),
    onSuccess: (data) => {
      setResult(data)
      setErrorMsg(null)
    },
    onError: (err: unknown) => {
      setErrorMsg(err instanceof Error ? err.message : String(err))
      setResult(null)
    },
  })

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    if (!message.trim()) return
    queryMut.mutate(message.trim())
  }

  return (
    <div className="p-6">
      <div className="flex items-center gap-2 mb-6">
        <Terminal size={20} className="text-gray-500" />
        <h1 className="text-xl font-semibold text-gray-900">쿼리 콘솔</h1>
      </div>
      <p className="text-sm text-gray-500 mb-4">자연어 질문을 입력하면 LLM이 SQL을 생성하고 결과를 반환합니다.</p>

      <form onSubmit={handleSubmit} className="flex gap-2 mb-6">
        <input
          value={message}
          onChange={e => setMessage(e.target.value)}
          placeholder="예: 지난달 가장 많이 팔린 상품 10개를 알려줘"
          className="flex-1 border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
        />
        <button
          type="submit"
          disabled={queryMut.isPending || !message.trim()}
          className="flex items-center gap-2 bg-blue-600 hover:bg-blue-700 disabled:opacity-50 text-white px-4 py-2 rounded-lg text-sm font-medium transition-colors"
        >
          <Send size={15} className={queryMut.isPending ? 'animate-pulse' : ''} />
          {queryMut.isPending ? '실행 중…' : '실행'}
        </button>
      </form>

      {errorMsg && (
        <div className="mb-4 bg-red-50 border border-red-200 text-red-600 text-sm px-4 py-2 rounded-lg">
          오류: {errorMsg}
        </div>
      )}

      {result && (
        <div className="space-y-4">
          {result.intent && (
            <div className="text-xs text-gray-500">
              의도: <span className="font-mono text-gray-700">{result.intent}</span>
            </div>
          )}
          {result.generatedSql && (
            <div>
              <p className="text-xs font-semibold text-gray-600 mb-1.5">생성된 SQL</p>
              <pre className="bg-gray-900 text-gray-100 text-xs rounded-lg p-4 overflow-x-auto whitespace-pre-wrap">
                {result.generatedSql}
              </pre>
            </div>
          )}
          <div>
            <p className="text-xs font-semibold text-gray-600 mb-1.5">응답</p>
            <div className="bg-white border border-gray-200 rounded-lg p-4 text-sm text-gray-800 whitespace-pre-wrap">
              {result.content}
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
