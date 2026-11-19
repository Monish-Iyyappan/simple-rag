import {useState} from 'react'
import './App.css'

type AskResponse = {
  answer: string
  sources: string[]
}

function App(){
  const [question, setQuestion] = useState('')
  const [answer, setAnswer] = useState('')
  const [sources, setSources] = useState<string[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  async function ask() {
    if (!question.trim()) return
    setLoading(true)
    setError('')
    setAnswer('')
    setSources([])
    try{
      const res = await fetch('/api/ask',{
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({question}),
      })
      if(!res.ok) throw new Error(await res.text())
      const data: AskResponse = await res.json()
      setAnswer(data.answer)
      setSources(data.sources)
    } catch(e) {
      setError(e instanceof Error ? e.message : 'Something went wrong')
    } finally{
      setLoading(false)
    }
  }

return (
  <main className="app">
    <h1>Simple RAG</h1>

    <div className="row">
      <input
        value={question}
        onChange={(e) => setQuestion(e.target.value)}
        onKeyDown={(e) => e.key === 'Enter' && ask()}
        placeholder="Type your question"
      />
      <button onClick={ask} disabled={loading}>
        {loading ? 'Thinking...': 'Ask'}
      </button>
    </div>

    {error && <p className="error">{error}</p>}
    {answer && <p className="answer">{answer}</p>}

    {sources.map((s,i) => (
      <div className="source" key={i}>{s}</div>
    ))}
  </main>
)
}

export default App
