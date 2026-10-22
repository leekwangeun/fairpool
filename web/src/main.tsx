import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter, Route, Routes } from 'react-router-dom';
import './index.css';
import Home from './pages/Home';
import NewTrip from './pages/NewTrip';
import DriverTrip from './pages/DriverTrip';
import PassengerTrip from './pages/PassengerTrip';
import CarpoolLink from './pages/CarpoolLink';
import VehicleLink from './pages/VehicleLink';
import FolderAdmin from './pages/FolderAdmin';
import SettlePage from './pages/SettlePage';
import HowItWorks from './pages/HowItWorks';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<Home />} />
        <Route path="/new" element={<NewTrip />} />
        <Route path="/d/:code" element={<DriverTrip />} />
        <Route path="/t/:code" element={<PassengerTrip />} />
        <Route path="/t/:code/how" element={<HowItWorks />} />
        <Route path="/c/:code" element={<CarpoolLink />} />
        <Route path="/link/:code" element={<VehicleLink />} />
        <Route path="/f/:code" element={<FolderAdmin />} />
        <Route path="/s/:code" element={<SettlePage />} />
        <Route path="*" element={<Home />} />
      </Routes>
    </BrowserRouter>
  </React.StrictMode>,
);
