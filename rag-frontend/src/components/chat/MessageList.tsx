import React, { useEffect, useRef, useState } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { Clock } from 'lucide-react'
import type { Message, Intent } from '../../types'
import CitationCard from './CitationCard'

interface MessageListProps {
  messages: Message[]
  isLoading: boolean
  loadingStartTime: number | null
}

const INTENT_BADGE: Record<Intent, string> = {
  RAG: '📄 RAG',
  SQL: '📊 SQL',
  HYBRID: '🔀 HYBRID',
  IMAGE: '🖼️ IMAGE',
  FILE: '📎 FILE',
}

/** ms → "30s" / "1m20s" / "2h5m" 형식 */
function formatElapsed(ms: number): string {
  const s = Math.floor(ms / 1000)
  if (s < 60) return `${s}s`
  const m = Math.floor(s / 60)
  const remS = s % 60
  if (m < 60) return remS > 0 ? `${m}m${remS}s` : `${m}m`
  const h = Math.floor(m / 60)
  const remM = m % 60
  return remM > 0 ? `${h}h${remM}m` : `${h}h`
}

/** 로딩 중 초단위 카운트업 타이머 */
function ElapsedTimer({ startTime }: { startTime: number }) {
  const [elapsed, setElapsed] = useState(Date.now() - startTime)

  useEffect(() => {
    const id = setInterval(() => {
      setElapsed(Date.now() - startTime)
    }, 1000)
    return () => clearInterval(id)
  }, [startTime])

  return (
    <span className="ml-2 text-xs text-gray-400 tabular-nums">
      {formatElapsed(elapsed)}
    </span>
  )
}

export default function MessageList({ messages, isLoading, loadingStartTime }: MessageListProps) {
  const bottomRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages, isLoading])

  return (
    <div className="flex-1 overflow-y-auto px-4 py-6 space-y-4">
      {messages.length === 0 && !isLoading && (
        <div className="flex items-center justify-center h-full text-gray-400 text-sm">
          메시지를 입력해 대화를 시작하세요.
        </div>
      )}

      {messages.map(msg => (
        <div
          key={msg.id}
          className={`flex ${msg.role === 'user' ? 'justify-end' : 'justify-start'}`}
        >
          <div
            className={`max-w-[72%] rounded-2xl px-4 py-3 text-sm ${
              msg.role === 'user'
                ? 'bg-blue-600 text-white'
                : 'bg-white border border-gray-200 text-gray-800'
            }`}
          >
            {/* intent 배지 */}
            {msg.role === 'assistant' && msg.intent && (
              <span className="inline-block text-xs text-gray-500 bg-gray-100 rounded px-2 py-0.5 mb-2">
                {INTENT_BADGE[msg.intent] ?? msg.intent}
              </span>
            )}

            {/* 내용 */}
            {msg.role === 'user' ? (
              <p className="whitespace-pre-wrap">{msg.content}</p>
            ) : (
              <div className="prose prose-sm max-w-none prose-p:my-1 prose-li:my-0.5">
                <ReactMarkdown remarkPlugins={[remarkGfm]}>{msg.content}</ReactMarkdown>
              </div>
            )}

            {/* generatedSql */}
            {msg.generatedSql && (
              <div className="mt-3">
                <p className="text-xs text-gray-500 mb-1 font-medium">생성된 SQL</p>
                <pre className="bg-gray-50 border border-gray-200 rounded-lg p-3 text-xs overflow-x-auto text-gray-800 whitespace-pre-wrap">
                  {msg.generatedSql}
                </pre>
              </div>
            )}

            {/* citations */}
            {msg.citations && msg.citations.length > 0 && (
              <CitationCard citations={msg.citations} />
            )}

            {/* 응답 경과 시간 */}
            {msg.role === 'assistant' && msg.elapsedMs != null && (
              <div className="flex items-center gap-1 mt-2 text-xs text-gray-400">
                <Clock size={11} />
                <span>{formatElapsed(msg.elapsedMs)}</span>
              </div>
            )}
          </div>
        </div>
      ))}

      {/* 로딩 인디케이터 + 실시간 타이머 */}
      {isLoading && (
        <div className="flex justify-start">
          <div className="bg-white border border-gray-200 rounded-2xl px-4 py-3 flex items-center gap-1">
            <span className="w-2 h-2 bg-gray-400 rounded-full animate-bounce [animation-delay:0ms]" />
            <span className="w-2 h-2 bg-gray-400 rounded-full animate-bounce [animation-delay:150ms]" />
            <span className="w-2 h-2 bg-gray-400 rounded-full animate-bounce [animation-delay:300ms]" />
            {loadingStartTime != null && (
              <ElapsedTimer startTime={loadingStartTime} />
            )}
          </div>
        </div>
      )}

      <div ref={bottomRef} />
    </div>
  )
}
