import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter, Route, Routes } from 'react-router-dom'
import Shell from './components/Shell'
import Today from './pages/Today'
import Meeting from './pages/Meeting'
import Import from './pages/Import'
import People from './pages/People'
import Search from './pages/Search'
import Settings from './pages/Settings'
// Fonts are bundled, not fetched: the app makes no outbound requests (NF-1).
import '@fontsource/instrument-sans/400.css'
import '@fontsource/instrument-sans/500.css'
import '@fontsource/instrument-sans/600.css'
import '@fontsource/instrument-sans/700.css'
import '@fontsource/jetbrains-mono/400.css'
import '@fontsource/jetbrains-mono/500.css'
import './styles/global.css'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter>
      <Shell>
        <Routes>
          <Route path="/" element={<Today />} />
          <Route path="/day/:date" element={<Today />} />
          <Route path="/day/:date/:conversationId" element={<Today />} />
          <Route path="/meeting" element={<Meeting />} />
          <Route path="/meeting/:id" element={<Meeting />} />
          <Route path="/import" element={<Import />} />
          <Route path="/people" element={<People />} />
          <Route path="/search" element={<Search />} />
          <Route path="/settings" element={<Settings />} />
        </Routes>
      </Shell>
    </BrowserRouter>
  </StrictMode>,
)
