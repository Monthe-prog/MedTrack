// ============ STATE MANAGEMENT ============
const appState = {
    isAuthenticated: false,
    user: {
        email: '',
        role: '' // 'doctor' or 'patient'
    },
    prescriptions: [],
    patients: [],
    intakeLogs: [],
    messages: {
        'doctor-patient-1': [],
        'doctor-patient-2': [],
        'doctor-patient-3': [],
        'patient': []
    },
    currentChatRoom: null
};

// Mock data
const mockPatients = [
    { id: 1, name: 'John Doe', email: 'john@example.com', age: 45, condition: 'Hypertension' },
    { id: 2, name: 'Jane Smith', email: 'jane@example.com', age: 52, condition: 'Diabetes' },
    { id: 3, name: 'Bob Wilson', email: 'bob@example.com', age: 38, condition: 'Asthma' }
];

const mockMedications = [
    { id: 1, name: 'Lisinopril', dosage: '10mg', time: '09:00 AM', frequency: 'Once daily', status: 'pending' },
    { id: 2, name: 'Metformin', dosage: '500mg', time: '02:00 PM', frequency: 'Twice daily', status: 'pending' },
    { id: 3, name: 'Aspirin', dosage: '81mg', time: '08:00 AM', frequency: 'Once daily', status: 'taken' }
];

const mockPrescriptions = [
    {
        id: 'RX001',
        patientId: 1,
        patientName: 'J*** D**',
        medication: 'Lisinopril',
        dosage: '10mg',
        frequency: 'Once daily',
        startDate: '2026-04-01',
        endDate: '2026-07-01',
        status: 'active',
        notes: 'For blood pressure management'
    },
    {
        id: 'RX002',
        patientId: 2,
        patientName: 'J*** S***',
        medication: 'Metformin',
        dosage: '500mg',
        frequency: 'Twice daily',
        startDate: '2026-03-15',
        endDate: '2026-09-15',
        status: 'active',
        notes: 'Diabetes management'
    }
];

// ============ INITIALIZATION ============
document.addEventListener('DOMContentLoaded', () => {
    initializeEventListeners();
    setDefaultDate();
});

function initializeEventListeners() {
    // Auth
    document.getElementById('loginForm').addEventListener('submit', handleLogin);
    document.getElementById('doctorLogout').addEventListener('click', handleLogout);
    document.getElementById('patientLogout').addEventListener('click', handleLogout);

    // Tab navigation
    document.querySelectorAll('.tab-btn').forEach(btn => {
        btn.addEventListener('click', (e) => switchTab(e.target.dataset.tab, e.target.closest('.tabs')));
    });

    // Doctor
    document.getElementById('createPrescriptionBtn').addEventListener('click', openPrescriptionModal);
    document.getElementById('prescriptionForm').addEventListener('submit', handleCreatePrescription);
    document.getElementById('closeModalBtn').addEventListener('click', closePrescriptionModal);
    document.getElementById('cancelModalBtn').addEventListener('click', closePrescriptionModal);
    document.getElementById('modalOverlay').addEventListener('click', closePrescriptionModal);

    // Chat
    document.getElementById('doctorChatSend').addEventListener('click', sendDoctorMessage);
    document.getElementById('doctorChatInput').addEventListener('keypress', (e) => {
        if (e.key === 'Enter') sendDoctorMessage();
    });

    document.getElementById('patientChatSend').addEventListener('click', sendPatientMessage);
    document.getElementById('patientChatInput').addEventListener('keypress', (e) => {
        if (e.key === 'Enter') sendPatientMessage();
    });
}

// ============ AUTHENTICATION ============
function handleLogin(e) {
    e.preventDefault();
    
    const email = document.getElementById('email').value;
    const password = document.getElementById('password').value;
    const role = document.getElementById('role').value;

    if (!email || !password || !role) {
        alert('Please fill in all fields');
        return;
    }

    appState.isAuthenticated = true;
    appState.user = { email, role };

    // Hide auth screen and show appropriate dashboard
    document.getElementById('authScreen').classList.remove('active');
    
    if (role === 'doctor') {
        document.getElementById('doctorScreen').classList.add('active');
        document.getElementById('doctorEmail').textContent = email;
        initializeDoctorDashboard();
    } else {
        document.getElementById('patientScreen').classList.add('active');
        document.getElementById('patientEmail').textContent = email;
        initializePatientDashboard();
    }

    // Clear form
    document.getElementById('loginForm').reset();
}

function handleLogout() {
    appState.isAuthenticated = false;
    appState.user = { email: '', role: '' };

    // Hide dashboards and show auth screen
    document.getElementById('doctorScreen').classList.remove('active');
    document.getElementById('patientScreen').classList.remove('active');
    document.getElementById('authScreen').classList.add('active');

    // Reset active tabs
    document.querySelectorAll('.tab-btn').forEach(btn => btn.classList.remove('active'));
    document.querySelectorAll('.tab-btn:first-child').forEach(btn => btn.classList.add('active'));
    document.querySelectorAll('.tab-content').forEach(content => content.classList.remove('active'));
    document.querySelectorAll('.tab-content:first-child').forEach(content => content.classList.add('active'));
}

// ============ DOCTOR DASHBOARD ============
function initializeDoctorDashboard() {
    appState.patients = mockPatients;
    appState.prescriptions = mockPrescriptions;
    
    renderPrescriptions();
    renderPatients();
    renderDoctorChatRooms();
    populatePatientSelect();
}

function renderPrescriptions() {
    const container = document.getElementById('prescriptionsList');
    container.innerHTML = '';

    appState.prescriptions.forEach(rx => {
        const card = document.createElement('div');
        card.className = 'card';
        card.innerHTML = `
            <div class="card-header">
                <div>
                    <h3 class="card-title">${rx.medication}</h3>
                    <p class="card-subtitle">Patient: ${rx.patientName}</p>
                </div>
                <span class="card-status ${rx.status === 'active' ? 'status-active' : 'status-pending'}">
                    ${rx.status.charAt(0).toUpperCase() + rx.status.slice(1)}
                </span>
            </div>
            <p class="card-label">${rx.dosage} • ${rx.frequency}</p>
            <p style="font-size: 13px; color: var(--gray-600); margin-bottom: 12px;">
                ${rx.startDate} to ${rx.endDate}
            </p>
            <p style="font-size: 13px; color: var(--gray-600); margin-bottom: 16px; font-style: italic;">
                "${rx.notes}"
            </p>
            <div class="card-footer">
                <button class="btn btn-sm btn-primary" onclick="editPrescription('${rx.id}')">Edit</button>
                <button class="btn btn-sm btn-outline" onclick="downloadPrescription('${rx.id}')">Download PDF</button>
            </div>
        `;
        container.appendChild(card);
    });
}

function renderPatients() {
    const container = document.getElementById('patientsList');
    container.innerHTML = '';

    appState.patients.forEach(patient => {
        const card = document.createElement('div');
        card.className = 'card';
        card.innerHTML = `
            <h3 class="card-title">${patient.name}</h3>
            <p class="card-subtitle">${patient.email}</p>
            <p style="font-size: 13px; color: var(--gray-600); margin-bottom: 8px;">
                Age: ${patient.age} | Condition: ${patient.condition}
            </p>
            <p style="font-size: 13px; color: var(--gray-500); margin-bottom: 16px;">
                ID: ${patient.id}
            </p>
            <div class="card-footer">
                <button class="btn btn-sm btn-primary" onclick="viewPatientDetails(${patient.id})">View Details</button>
                <button class="btn btn-sm btn-secondary" onclick="createPrescriptionForPatient(${patient.id})">New Rx</button>
            </div>
        `;
        container.appendChild(card);
    });
}

function renderDoctorChatRooms() {
    const container = document.getElementById('doctorChatRooms');
    container.innerHTML = '';

    mockPatients.forEach((patient, idx) => {
        const btn = document.createElement('button');
        btn.className = 'chat-room-btn';
        btn.textContent = patient.name;
        btn.onclick = () => selectDoctorChatRoom(`doctor-patient-${patient.id}`);
        container.appendChild(btn);
    });
}

function selectDoctorChatRoom(roomId) {
    appState.currentChatRoom = roomId;
    
    // Update active state
    document.querySelectorAll('.chat-room-btn').forEach(btn => btn.classList.remove('active'));
    event.target.classList.add('active');

    // Show chat area
    document.getElementById('doctorChatArea').classList.remove('hidden');

    // Load chat
    loadDoctorChatMessages(roomId);
}

function loadDoctorChatMessages(roomId) {
    const messagesContainer = document.getElementById('doctorChatMessages');
    const titleContainer = document.getElementById('doctorChatTitle');
    
    const patientId = roomId.split('-')[2];
    const patient = mockPatients.find(p => p.id == patientId);
    
    titleContainer.textContent = patient ? patient.name : 'Patient';

    const messages = appState.messages[roomId] || [];
    messagesContainer.innerHTML = '';

    messages.forEach(msg => {
        const msgDiv = document.createElement('div');
        msgDiv.className = `chat-message ${msg.sender === 'doctor' ? 'sent' : 'received'}`;
        msgDiv.innerHTML = `
            <div class="chat-bubble">${msg.text}</div>
            <span class="chat-time">${msg.time}</span>
        `;
        messagesContainer.appendChild(msgDiv);
    });

    messagesContainer.scrollTop = messagesContainer.scrollHeight;
}

function sendDoctorMessage() {
    const input = document.getElementById('doctorChatInput');
    const message = input.value.trim();

    if (!message || !appState.currentChatRoom) return;

    if (!appState.messages[appState.currentChatRoom]) {
        appState.messages[appState.currentChatRoom] = [];
    }

    const time = new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    
    appState.messages[appState.currentChatRoom].push({
        sender: 'doctor',
        text: message,
        time: time
    });

    input.value = '';
    loadDoctorChatMessages(appState.currentChatRoom);

    // Simulate patient response
    setTimeout(() => {
        appState.messages[appState.currentChatRoom].push({
            sender: 'patient',
            text: 'Thank you for the message, Doctor.',
            time: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
        });
        loadDoctorChatMessages(appState.currentChatRoom);
    }, 1000);
}

function populatePatientSelect() {
    const select = document.getElementById('rxPatient');
    select.innerHTML = '<option value="">Choose a patient</option>';
    
    mockPatients.forEach(patient => {
        const option = document.createElement('option');
        option.value = patient.id;
        option.textContent = patient.name;
        select.appendChild(option);
    });
}

function editPrescription(rxId) {
    alert(`Edit prescription ${rxId} - Feature coming soon`);
}

function downloadPrescription(rxId) {
    const rx = appState.prescriptions.find(r => r.id === rxId);
    if (!rx) return;

    const pdfContent = `
PRESCRIPTION
====================
Medication: ${rx.medication}
Dosage: ${rx.dosage}
Frequency: ${rx.frequency}
Duration: ${rx.startDate} to ${rx.endDate}
Notes: ${rx.notes}
====================
Rx ID: ${rxId}
Date: ${new Date().toLocaleDateString()}
    `;

    const blob = new Blob([pdfContent], { type: 'text/plain' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `prescription_${rxId}.txt`;
    a.click();
    URL.revokeObjectURL(url);
}

function viewPatientDetails(patientId) {
    const patient = mockPatients.find(p => p.id === patientId);
    alert(`Patient: ${patient.name}\nEmail: ${patient.email}\nAge: ${patient.age}\nCondition: ${patient.condition}`);
}

function createPrescriptionForPatient(patientId) {
    document.getElementById('rxPatient').value = patientId;
    openPrescriptionModal();
}

function openPrescriptionModal() {
    document.getElementById('prescriptionModal').classList.remove('hidden');
    document.getElementById('modalOverlay').classList.remove('hidden');
}

function closePrescriptionModal() {
    document.getElementById('prescriptionModal').classList.add('hidden');
    document.getElementById('modalOverlay').classList.add('hidden');
    document.getElementById('prescriptionForm').reset();
}

function handleCreatePrescription(e) {
    e.preventDefault();

    const patientId = document.getElementById('rxPatient').value;
    const medication = document.getElementById('rxMedication').value;
    const dosage = document.getElementById('rxDosage').value;
    const frequency = document.getElementById('rxFrequency').value;
    const startDate = document.getElementById('rxStartDate').value;
    const endDate = document.getElementById('rxEndDate').value;
    const notes = document.getElementById('rxNotes').value;

    if (!patientId || !medication || !dosage || !frequency || !startDate || !endDate) {
        alert('Please fill in all required fields');
        return;
    }

    const newRx = {
        id: `RX${Date.now()}`,
        patientId: parseInt(patientId),
        patientName: mockPatients.find(p => p.id == patientId).name,
        medication,
        dosage,
        frequency,
        startDate,
        endDate,
        status: 'active',
        notes
    };

    appState.prescriptions.push(newRx);
    renderPrescriptions();
    closePrescriptionModal();
    alert('Prescription created successfully!');
}

// ============ PATIENT DASHBOARD ============
function initializePatientDashboard() {
    appState.intakeLogs = [];
    
    // Generate today's medications
    renderSchedule();
    renderHistory();
    loadPatientChatMessages();
}

function renderSchedule() {
    const container = document.getElementById('scheduleList');
    container.innerHTML = '';

    mockMedications.forEach(med => {
        const card = document.createElement('div');
        card.className = 'medication-card';
        card.innerHTML = `
            <div class="medication-info">
                <div class="medication-time">${med.time}</div>
                <h3>${med.name}</h3>
                <p>${med.dosage} • ${med.frequency}</p>
            </div>
            <div class="medication-actions">
                ${med.status === 'taken' 
                    ? `<button class="btn btn-success btn-sm" disabled>✓ Taken</button>`
                    : `
                        <button class="btn btn-success btn-sm" onclick="markMedicationTaken('${med.id}')">Take Now</button>
                        <button class="btn btn-danger btn-sm" onclick="markMedicationMissed('${med.id}')">Miss</button>
                    `
                }
            </div>
        `;
        container.appendChild(card);
    });

    updateComplianceRing();
}

function updateComplianceRing() {
    const taken = mockMedications.filter(m => m.status === 'taken').length;
    const total = mockMedications.length;
    const percentage = Math.round((taken / total) * 100);

    document.getElementById('compliancePercent').textContent = `${percentage}%`;

    const circumference = 2 * Math.PI * 45; // radius = 45
    const offset = circumference - (percentage / 100) * circumference;
    document.getElementById('ringProgress').style.strokeDashoffset = offset;
}

function markMedicationTaken(medId) {
    const med = mockMedications.find(m => m.id == medId);
    if (med) {
        med.status = 'taken';
        
        appState.intakeLogs.push({
            date: new Date().toLocaleDateString(),
            medication: med.name,
            dosage: med.dosage,
            status: 'taken'
        });

        renderSchedule();
    }
}

function markMedicationMissed(medId) {
    const med = mockMedications.find(m => m.id == medId);
    if (med) {
        med.status = 'missed';
        
        appState.intakeLogs.push({
            date: new Date().toLocaleDateString(),
            medication: med.name,
            dosage: med.dosage,
            status: 'missed'
        });

        renderSchedule();
    }
}

function renderHistory() {
    const container = document.getElementById('historyList');
    container.innerHTML = '';

    // Generate 30-day history
    const today = new Date();
    for (let i = 29; i >= 0; i--) {
        const date = new Date(today);
        date.setDate(date.getDate() - i);
        const dateStr = date.toLocaleDateString('en-US', { weekday: 'short', month: 'short', day: 'numeric' });

        // Simulate historical data
        const statuses = [
            { medication: 'Lisinopril 10mg', status: 'taken' },
            { medication: 'Metformin 500mg', status: Math.random() > 0.2 ? 'taken' : 'missed' }
        ];

        const item = document.createElement('div');
        item.className = 'history-item';
        item.innerHTML = `
            <div>
                <div class="history-date">${dateStr}</div>
                <div class="history-medication">
                    ${statuses.map(s => `<div>${s.medication} <span class="history-status-icon">${s.status === 'taken' ? '✓' : '✕'}</span></div>`).join('')}
                </div>
            </div>
        `;
        container.appendChild(item);
    }
}

function loadPatientChatMessages() {
    const messagesContainer = document.getElementById('patientChatMessages');
    const messages = appState.messages['patient'] || [];

    messagesContainer.innerHTML = '';

    messages.forEach(msg => {
        const msgDiv = document.createElement('div');
        msgDiv.className = `chat-message ${msg.sender === 'patient' ? 'sent' : 'received'}`;
        msgDiv.innerHTML = `
            <div class="chat-bubble">${msg.text}</div>
            <span class="chat-time">${msg.time}</span>
        `;
        messagesContainer.appendChild(msgDiv);
    });

    messagesContainer.scrollTop = messagesContainer.scrollHeight;
}

function sendPatientMessage() {
    const input = document.getElementById('patientChatInput');
    const message = input.value.trim();

    if (!message) return;

    const time = new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    
    if (!appState.messages['patient']) {
        appState.messages['patient'] = [];
    }

    appState.messages['patient'].push({
        sender: 'patient',
        text: message,
        time: time
    });

    input.value = '';
    loadPatientChatMessages();

    // Simulate doctor response
    setTimeout(() => {
        appState.messages['patient'].push({
            sender: 'doctor',
            text: 'Thank you for your message. How are you feeling today?',
            time: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
        });
        loadPatientChatMessages();
    }, 1500);
}

// ============ TAB NAVIGATION ============
function switchTab(tabName, tabsContainer) {
    // Get the dashboard container
    const dashboard = tabsContainer.closest('.dashboard-container');

    // Hide all tab contents in this dashboard
    dashboard.querySelectorAll('.tab-content').forEach(content => {
        content.classList.remove('active');
    });

    // Remove active class from all tab buttons
    dashboard.querySelectorAll('.tab-btn').forEach(btn => {
        btn.classList.remove('active');
    });

    // Show selected tab content
    const tabContent = dashboard.querySelector(`#${tabName}-tab`);
    if (tabContent) {
        tabContent.classList.add('active');
    }

    // Add active class to clicked button
    event.target.classList.add('active');
}

// ============ UTILITIES ============
function setDefaultDate() {
    const today = new Date().toISOString().split('T')[0];
    const endDate = new Date();
    endDate.setDate(endDate.getDate() + 30);
    const endDateStr = endDate.toISOString().split('T')[0];
    
    document.getElementById('rxStartDate').value = today;
    document.getElementById('rxEndDate').value = endDateStr;
}

// Initialize app
console.log('MedTrack Web App Loaded');
