import { Routes, Route, Navigate } from 'react-router-dom';
import AppLayout from './components/AppLayout';
import Overview from './pages/Overview';
import AgentList from './pages/agents/AgentList';
import ChatPage from './pages/chat/ChatPage';
import PluginMarketplace from './pages/plugins/PluginMarketplace';
import LogsPage from './pages/ops/LogsPage';
import DiagnosisPage from './pages/ops/DiagnosisPage';
import PromptOptimizePage from './pages/ops/PromptOptimizePage';
import ToolsPage from './pages/ops/ToolsPage';
import QuotaPage from './pages/ops/QuotaPage';
import KnowledgeBasePage from './pages/rag/KnowledgeBasePage';
import SessionsPage from './pages/sessions/SessionsPage';
import SkillsPage from './pages/skills/SkillsPage';
import WorkflowsPage from './pages/workflows/WorkflowsPage';
import FilesPage from './pages/files/FilesPage';
import ModelSettingsPage from './pages/settings/ModelSettingsPage';

export default function App() {
  return (
    <Routes>
      <Route element={<AppLayout />}>
        <Route path="/" element={<Navigate to="/overview" replace />} />
        <Route path="/overview" element={<Overview />} />
        <Route path="/agents" element={<AgentList />} />
        <Route path="/chat" element={<ChatPage />} />
        <Route path="/sessions" element={<SessionsPage />} />
        <Route path="/knowledge-bases" element={<KnowledgeBasePage />} />
        <Route path="/workflows" element={<WorkflowsPage />} />
        <Route path="/skills" element={<SkillsPage />} />
        <Route path="/plugins" element={<PluginMarketplace />} />
        <Route path="/files" element={<FilesPage />} />
        <Route path="/logs" element={<LogsPage />} />
        <Route path="/diagnosis" element={<DiagnosisPage />} />
        <Route path="/prompt" element={<PromptOptimizePage />} />
        <Route path="/tools" element={<ToolsPage />} />
        <Route path="/quota" element={<QuotaPage />} />
        <Route path="/settings" element={<ModelSettingsPage />} />
      </Route>
    </Routes>
  );
}