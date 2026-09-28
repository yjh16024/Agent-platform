import { Routes, Route, Navigate } from 'react-router-dom';
import AppLayout from './components/AppLayout';
import RequireAuth from './components/RequireAuth';
import LoginPage from './pages/LoginPage';
import Overview from './pages/Overview';
import AgentList from './pages/agents/AgentList';
import ChatPage from './pages/chat/ChatPage';
import PluginMarketplace from './pages/plugins/PluginMarketplace';
import OpsLogsPage from './pages/ops/OpsLogsPage';
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
import SkinMarketPage from './pages/skins/SkinMarketPage';
import UserManagePage from './pages/system/UserManagePage';
import RoleManagePage from './pages/system/RoleManagePage';
import DictManagePage from './pages/system/DictManagePage';
import NotificationsPage from './pages/notifications/NotificationsPage';
import MemoryPage from './pages/memory/MemoryPage';
import ApprovalPage from './pages/ops/ApprovalPage';

export default function App() {
  return (
    <Routes>
      {/* 登录页在 AppLayout 之外：未登录时不该出现任何导航入口 */}
      <Route path="/login" element={<LoginPage />} />
      <Route
        element={
          <RequireAuth>
            <AppLayout />
          </RequireAuth>
        }
      >
        <Route path="/" element={<Navigate to="/overview" replace />} />
        <Route path="/overview" element={<Overview />} />
        <Route path="/agents" element={<AgentList />} />
        <Route path="/chat" element={<ChatPage />} />
        <Route path="/sessions" element={<SessionsPage />} />
        <Route path="/knowledge-bases" element={<KnowledgeBasePage />} />
        <Route path="/workflows" element={<WorkflowsPage />} />
        <Route path="/skills" element={<SkillsPage />} />
        <Route path="/skins" element={<SkinMarketPage />} />
        <Route path="/plugins" element={<PluginMarketplace />} />
        <Route path="/files" element={<FilesPage />} />
        {/*
          「日志与观测」：运行日志 / 智能体可观测性 / 操作日志 / 统计报表
          四个同源页面合并成一个入口（读的是同一批表，见 OpsLogsPage 的类注释）。
          三个旧路径**重定向**过去而不是删除 —— 老书签、以及 Overview 首页
          那个指向 /logs 的入口卡片都还在用它们。
        */}
        <Route path="/logs" element={<OpsLogsPage />} />
        <Route path="/observability" element={<Navigate to="/logs?tab=observability" replace />} />
        <Route path="/audit" element={<Navigate to="/logs?tab=audit" replace />} />
        <Route path="/reports" element={<Navigate to="/logs?tab=reports" replace />} />
        <Route path="/diagnosis" element={<DiagnosisPage />} />
        <Route path="/prompt" element={<PromptOptimizePage />} />
        <Route path="/tools" element={<ToolsPage />} />
        <Route path="/quota" element={<QuotaPage />} />
        <Route path="/settings" element={<ModelSettingsPage />} />
        <Route path="/system/users" element={<UserManagePage />} />
        <Route path="/system/roles" element={<RoleManagePage />} />
        <Route path="/system/dicts" element={<DictManagePage />} />
        <Route path="/notifications" element={<NotificationsPage />} />
        <Route path="/memory" element={<MemoryPage />} />
        <Route path="/approvals" element={<ApprovalPage />} />
      </Route>
    </Routes>
  );
}
