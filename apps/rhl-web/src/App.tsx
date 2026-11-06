import React, { useState, useEffect } from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { AuthProvider } from './context/AuthContext';
import { Navbar } from './components/Navbar';
import { ProtectedRoute } from './components/ProtectedRoute';
import { LoginPage } from './pages/LoginPage';
import { RegisterPage } from './pages/RegisterPage';
import { DashboardPage } from './pages/DashboardPage';
import { CustomerAppPage } from './pages/CustomerAppPage';
import { DriverAppPage } from './pages/DriverAppPage';
import { FinanceConsolePage } from './pages/FinanceConsolePage';
import { NotFoundPage } from './pages/NotFoundPage';
import { DevConsole } from './dev/DevConsole';
import { apiLogger } from './services/apiLogger';

export const AppContent: React.FC = () => {
  const [devConsoleOpen, setDevConsoleOpen] = useState(false);
  const [logCount, setLogCount] = useState(0);

  useEffect(() => {
    const unsub = apiLogger.subscribe(() => {
      setLogCount(apiLogger.getLogs().length);
    });
    return unsub;
  }, []);

  // Keyboard shortcut Ctrl + ` to toggle dev console (development only)
  useEffect(() => {
    if (!import.meta.env.DEV) return;
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.ctrlKey && e.key === '`') {
        e.preventDefault();
        setDevConsoleOpen((prev) => !prev);
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, []);

  return (
    <div style={{ minHeight: '100vh', display: 'flex', flexDirection: 'column' }}>
      <Navbar
        onToggleDevConsole={() => setDevConsoleOpen((prev) => !prev)}
        devConsoleOpen={devConsoleOpen}
        logCount={logCount}
      />

      <main style={{ flex: 1 }}>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/register" element={<RegisterPage />} />

          {/* Existing Developer / API Testing Dashboard (100% PRESERVED) */}
          <Route
            path="/"
            element={
              <ProtectedRoute>
                <DashboardPage />
              </ProtectedRoute>
            }
          />
          <Route
            path="/dashboard"
            element={
              <ProtectedRoute>
                <DashboardPage />
              </ProtectedRoute>
            }
          />

          {/* Customer Ride-Hailing Application */}
          <Route
            path="/customer"
            element={
              <ProtectedRoute>
                <CustomerAppPage />
              </ProtectedRoute>
            }
          />

          {/* Driver Ride-Hailing Application */}
          <Route
            path="/driver"
            element={
              <ProtectedRoute>
                <DriverAppPage />
              </ProtectedRoute>
            }
          />

          {/* Finance Console (RBAC: FINANCE_STAFF or ADMINISTRATOR) */}
          <Route
            path="/finance"
            element={
              <ProtectedRoute requiredRoles={['FINANCE_STAFF', 'ADMINISTRATOR']}>
                <FinanceConsolePage />
              </ProtectedRoute>
            }
          />

          <Route path="/404" element={<NotFoundPage />} />
          <Route path="*" element={<Navigate to="/404" replace />} />
        </Routes>
      </main>

      {import.meta.env.DEV && (
        <DevConsole isOpen={devConsoleOpen} onClose={() => setDevConsoleOpen(false)} />
      )}
    </div>
  );
};

export default function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <AppContent />
      </AuthProvider>
    </BrowserRouter>
  );
}
