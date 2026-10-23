package com.kazihub.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kazihub.app.data.repository.*
import com.kazihub.app.model.*
import com.kazihub.app.ui.navigation.KaziHubDestination
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class KaziHubUiState(
    val currentDestination: KaziHubDestination = KaziHubDestination.WELCOME,
    val isSwahili: Boolean = false,
    val currentUser: UserProfile? = null,
    val isAuthenticated: Boolean = false,
    val activeRole: UserRole = UserRole.FREELANCER,
    val selectedCountry: EastAfricanCountry? = null,
    val selectedCategory: CreativeCategory? = null,
    val selectedEquipmentCategory: EquipmentCategory? = null,
    val searchQuery: String = "",
    val verifiedOnlyFilter: Boolean = false,
    val selectedCreative: CreativeProfile? = null,
    val selectedPortfolioItem: PortfolioItem? = null,
    val selectedEquipment: EquipmentItem? = null,
    val selectedBooking: BookingRecord? = null,
    val selectedTicket: SupportTicket? = null,
    val activeConversationId: String? = null,
    val isDemoModeNoticeVisible: Boolean = false,
    val isRoleSwitchDialogVisible: Boolean = false,
    val isLoading: Boolean = false,
    val roleSwitchErrorMessage: String? = null,
    val authErrorMessage: String? = null,
    val isFirebaseProvisioned: Boolean = false, // Honest state: Cloud Firebase not yet provisioned
    val cloudStorageNotice: String = "Cloud Storage is not provisioned. Selected profile photos are cached locally."
)

class KaziHubViewModel(
    private val userRepository: UserRepository = DemoUserRepository(),
    private val creativeRepository: CreativeRepository = DemoCreativeRepository(),
    private val equipmentRepository: EquipmentRepository = DemoEquipmentRepository(),
    private val bookingRepository: BookingRepository = DemoBookingRepository(),
    private val messageRepository: MessageRepository = DemoMessageRepository(),
    private val supportTicketRepository: SupportTicketRepository = DemoSupportTicketRepository(),
    private val locationRepository: LocationRepository = DemoLocationRepository()
) : ViewModel() {

    private val _uiState = MutableStateFlow(KaziHubUiState())
    val uiState: StateFlow<KaziHubUiState> = _uiState.asStateFlow()

    val creatives: StateFlow<List<CreativeProfile>> = creativeRepository.getFeaturedCreatives()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val equipmentList: StateFlow<List<EquipmentItem>> = equipmentRepository.getFeaturedEquipment()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val bookings: StateFlow<List<BookingRecord>> = bookingRepository.getAllBookings()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allUsers: StateFlow<List<UserProfile>> = userRepository.getAllUsers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val userTickets: StateFlow<List<SupportTicket>> = _uiState
        .map { it.currentUser?.id }
        .distinctUntilChanged()
        .flatMapLatest { userId ->
            if (userId != null) {
                supportTicketRepository.getUserTickets(userId)
            } else {
                flowOf(emptyList())
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allTickets: StateFlow<List<SupportTicket>> = supportTicketRepository.getAllTickets()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val conversations: StateFlow<List<ConversationSummary>> = messageRepository.getConversations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val notifications: StateFlow<List<AppNotification>> = messageRepository.getNotifications()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // Collect current user and sync auth state
        viewModelScope.launch {
            userRepository.getCurrentUser().collect { user ->
                _uiState.update {
                    it.copy(
                        currentUser = user,
                        isAuthenticated = user != null,
                        activeRole = user?.activeRole ?: UserRole.FREELANCER,
                        currentDestination = if (user == null) {
                            if (it.currentDestination in listOf(
                                KaziHubDestination.WELCOME,
                                KaziHubDestination.LOGIN,
                                KaziHubDestination.REGISTRATION,
                                KaziHubDestination.FORGOT_PASSWORD,
                                KaziHubDestination.LEGAL_TERMS
                            )) it.currentDestination else KaziHubDestination.WELCOME
                        } else {
                            if (it.currentDestination in listOf(
                                KaziHubDestination.WELCOME,
                                KaziHubDestination.LOGIN,
                                KaziHubDestination.REGISTRATION
                            )) KaziHubDestination.HOME else it.currentDestination
                        }
                    )
                }
            }
        }
    }

    // Navigation
    fun navigateTo(destination: KaziHubDestination) {
        // Guard unauthenticated navigation: only public unauthenticated destinations allowed
        if (!_uiState.value.isAuthenticated && destination !in listOf(
            KaziHubDestination.WELCOME,
            KaziHubDestination.LOGIN,
            KaziHubDestination.REGISTRATION,
            KaziHubDestination.FORGOT_PASSWORD,
            KaziHubDestination.LEGAL_TERMS
        )) {
            _uiState.update { it.copy(currentDestination = KaziHubDestination.LOGIN) }
            return
        }

        // Guard admin workspace navigation
        if (destination == KaziHubDestination.ADMIN_WORKSPACE || destination == KaziHubDestination.ADMIN_DASHBOARD) {
            val user = _uiState.value.currentUser
            val isAuthorizedAdmin = user?.isAdministrator == true
            if (!isAuthorizedAdmin) {
                _uiState.update { it.copy(currentDestination = KaziHubDestination.ADMIN_WORKSPACE) }
                return
            }
        }

        _uiState.update { it.copy(currentDestination = destination) }
    }

    fun toggleLanguage() {
        _uiState.update { it.copy(isSwahili = !it.isSwahili) }
    }

    // --- AUTHENTICATION FLOW (Phase 1.2) ---
    fun login(email: String, password: String, onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, authErrorMessage = null) }
            val res = userRepository.loginWithEmail(email, password)
            _uiState.update { it.copy(isLoading = false) }
            res.onSuccess { user ->
                _uiState.update {
                    it.copy(
                        currentUser = user,
                        isAuthenticated = true,
                        activeRole = user.activeRole,
                        authErrorMessage = null,
                        currentDestination = if (user.accountStatus == AccountStatus.PENDING_PROFILE_COMPLETION)
                            KaziHubDestination.PROFILE_COMPLETION else KaziHubDestination.HOME
                    )
                }
                onResult(true, null)
            }.onFailure { err ->
                val errorMsg = err.message ?: "Authentication failed."
                _uiState.update { it.copy(authErrorMessage = errorMsg) }
                onResult(false, errorMsg)
            }
        }
    }

    fun register(
        fullName: String,
        email: String,
        password: String,
        country: EastAfricanCountry,
        roles: Set<UserRole>,
        phoneNumber: String? = null,
        onResult: (Boolean, String?) -> Unit
    ) {
        // Enforce: Administrator role is NEVER allowed in public registration
        if (roles.contains(UserRole.ADMINISTRATOR)) {
            onResult(false, "Security violation: Administrator cannot be chosen during public registration.")
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, authErrorMessage = null) }
            val res = userRepository.registerUser(fullName, email, password, country, roles, phoneNumber)
            _uiState.update { it.copy(isLoading = false) }
            res.onSuccess { user ->
                _uiState.update {
                    it.copy(
                        currentUser = user,
                        isAuthenticated = true,
                        activeRole = user.activeRole,
                        authErrorMessage = null,
                        currentDestination = KaziHubDestination.PROFILE_COMPLETION
                    )
                }
                onResult(true, null)
            }.onFailure { err ->
                val errorMsg = err.message ?: "Registration failed."
                _uiState.update { it.copy(authErrorMessage = errorMsg) }
                onResult(false, errorMsg)
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            userRepository.logout()
            _uiState.update {
                it.copy(
                    currentUser = null,
                    isAuthenticated = false,
                    currentDestination = KaziHubDestination.WELCOME
                )
            }
        }
    }

    fun sendPasswordReset(email: String, onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            val res = userRepository.sendPasswordReset(email)
            res.onSuccess {
                onResult(true, null)
            }.onFailure {
                onResult(false, it.message ?: "Could not send reset link.")
            }
        }
    }

    // --- PROFILE PHOTO & PROFILE COMPLETION (Phase 1.2) ---
    fun completeProfile(
        displayName: String,
        profileImageUrl: String,
        bio: String,
        onResult: (Boolean, String?) -> Unit
    ) {
        viewModelScope.launch {
            val location = EastAfricaLocation(
                id = "loc_user_hub",
                country = _uiState.value.currentUser?.country ?: EastAfricanCountry.TANZANIA,
                primaryDivision = "Production Hub",
                secondaryDivision = "Commercial District",
                neighbourhoodOrArea = "Primary Operation Area"
            )
            val photoData = ProfilePhotoData(
                storagePath = "cached_local/profile_${System.currentTimeMillis()}.jpg",
                downloadUrl = profileImageUrl,
                uploadedAtEpochMs = System.currentTimeMillis(),
                fileSizeBytes = 245000,
                isVerified = true
            )
            val res = userRepository.completeProfile(displayName, profileImageUrl, bio, location, photoData)
            res.onSuccess { completed ->
                if (completed.hasRole(UserRole.FREELANCER)) {
                    val creative = CreativeProfile(
                        id = "cr_${completed.id}",
                        userId = completed.id,
                        fullName = completed.displayName,
                        titleTagline = bio.ifBlank { "Professional Creative" },
                        bio = bio,
                        primaryCategory = CreativeCategory.CINEMATOGRAPHY,
                        additionalCategories = emptyList(),
                        primaryLocation = location,
                        indicativeDayRate = 1200000L,
                        currencyCode = location.country.currencyCode,
                        isVerifiedPro = completed.isIdentityVerified,
                        averageRating = 0.0,
                        reviewsCount = 0,
                        completedProjectsCount = 0,
                        languagesSpoken = listOf("English", "Swahili"),
                        profilePhoto = photoData
                    )
                    creativeRepository.registerCreativeProfile(creative)
                }
                _uiState.update {
                    it.copy(
                        currentUser = completed,
                        currentDestination = KaziHubDestination.HOME
                    )
                }
                onResult(true, null)
            }.onFailure { err ->
                onResult(false, err.message ?: "Could not complete profile.")
            }
        }
    }

    fun updateProfilePhoto(photoUrl: String, onResult: (Boolean, String?) -> Unit) {
        val user = _uiState.value.currentUser ?: return onResult(false, "No authenticated session")
        completeProfile(user.displayName, photoUrl, user.bio, onResult)
    }

    fun deleteProfilePhoto(onResult: (Boolean, String?) -> Unit) {
        val user = _uiState.value.currentUser ?: return onResult(false, "No authenticated session")
        viewModelScope.launch {
            val cleared = user.copy(profileImageUrl = null, photoData = null)
            _uiState.update { it.copy(currentUser = cleared) }
            onResult(true, null)
        }
    }

    // --- STRICT ROLE SWITCHING (Security Guard) ---
    fun switchActiveRole(role: UserRole) {
        if (role == UserRole.ADMINISTRATOR) {
            _uiState.update {
                it.copy(
                    roleSwitchErrorMessage = "Unauthorized: Administrator permissions cannot be granted through client role switcher. Dedicated server verification required."
                )
            }
            return
        }

        viewModelScope.launch {
            val success = userRepository.switchActiveRole(role)
            if (success) {
                _uiState.update {
                    it.copy(
                        activeRole = role,
                        isRoleSwitchDialogVisible = false,
                        roleSwitchErrorMessage = null
                    )
                }
            } else {
                _uiState.update {
                    it.copy(roleSwitchErrorMessage = "You do not have approved credentials for this role.")
                }
            }
        }
    }


    fun setRoleSwitchDialogVisible(visible: Boolean) {
        _uiState.update { it.copy(isRoleSwitchDialogVisible = visible, roleSwitchErrorMessage = null) }
    }

    fun setCountryFilter(country: EastAfricanCountry?) {
        _uiState.update { it.copy(selectedCountry = country) }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun selectCreative(creative: CreativeProfile) {
        _uiState.update { it.copy(selectedCreative = creative) }
        navigateTo(KaziHubDestination.FREELANCER_PROFILE)
    }

    fun selectPortfolioItem(item: PortfolioItem) {
        _uiState.update { it.copy(selectedPortfolioItem = item) }
        navigateTo(KaziHubDestination.PORTFOLIO_DETAIL)
    }

    fun selectEquipment(item: EquipmentItem) {
        _uiState.update { it.copy(selectedEquipment = item) }
        navigateTo(KaziHubDestination.EQUIPMENT_DETAIL)
    }

    fun selectBooking(booking: BookingRecord) {
        _uiState.update { it.copy(selectedBooking = booking) }
        navigateTo(KaziHubDestination.BOOKING_DETAIL)
    }

    fun selectTicket(ticket: SupportTicket) {
        _uiState.update { it.copy(selectedTicket = ticket) }
        navigateTo(KaziHubDestination.SUPPORT_TICKET_DETAIL)
    }

    // --- COMPLETE BOOKING LIFECYCLE & QUOTATIONS (Phase 1.2) ---
    fun prepareAndSendQuotation(
        bookingId: String,
        lineItems: List<String>,
        totalAmount: Long,
        notes: String,
        validUntil: String
    ) {
        viewModelScope.launch {
            val user = _uiState.value.currentUser
            val quote = Quotation(
                id = "qt_${System.currentTimeMillis()}",
                jobRequestId = bookingId,
                providerId = user?.id ?: "usr_talent",
                providerName = user?.displayName ?: "Creative Talent",
                lineItems = lineItems,
                totalAmount = totalAmount,
                currencyCode = _uiState.value.selectedBooking?.currencyCode ?: "TZS",
                notes = notes,
                validUntilDate = validUntil,
                isAccepted = false,
                isDeclined = false
            )
            bookingRepository.submitQuotation(bookingId, quote)
            bookingRepository.getBookingById(bookingId).firstOrNull()?.let { updated ->
                _uiState.update { it.copy(selectedBooking = updated) }
            }
            navigateTo(KaziHubDestination.BOOKING_DETAIL)
        }
    }

    fun acceptBookingQuotation(bookingId: String) {
        viewModelScope.launch {
            val user = _uiState.value.currentUser
            bookingRepository.acceptQuotation(bookingId, user?.id ?: "usr_client")
            bookingRepository.getBookingById(bookingId).firstOrNull()?.let { updated ->
                _uiState.update { it.copy(selectedBooking = updated) }
            }
        }
    }

    fun declineBookingQuotation(bookingId: String, reason: String) {
        viewModelScope.launch {
            val user = _uiState.value.currentUser
            bookingRepository.declineQuotation(bookingId, user?.id ?: "usr_client", reason)
            bookingRepository.getBookingById(bookingId).firstOrNull()?.let { updated ->
                _uiState.update { it.copy(selectedBooking = updated) }
            }
        }
    }

    fun requestQuotationRevisions(bookingId: String, revisionNotes: String) {
        viewModelScope.launch {
            val user = _uiState.value.currentUser
            bookingRepository.requestQuotationRevisions(bookingId, user?.id ?: "usr_client", revisionNotes)
            bookingRepository.getBookingById(bookingId).firstOrNull()?.let { updated ->
                _uiState.update { it.copy(selectedBooking = updated) }
            }
        }
    }

    fun confirmBookingHandover(bookingId: String, checklistNotes: String) {
        viewModelScope.launch {
            val user = _uiState.value.currentUser
            bookingRepository.confirmHandoverAndDispatch(bookingId, user?.displayName ?: "Authorized Lead", checklistNotes)
            bookingRepository.getBookingById(bookingId).firstOrNull()?.let { updated ->
                _uiState.update { it.copy(selectedBooking = updated) }
            }
        }
    }

    fun markWorkDelivered(bookingId: String, deliveryRef: String) {
        viewModelScope.launch {
            val user = _uiState.value.currentUser
            bookingRepository.markWorkDelivered(bookingId, user?.displayName ?: "Service Provider", deliveryRef)
            bookingRepository.getBookingById(bookingId).firstOrNull()?.let { updated ->
                _uiState.update { it.copy(selectedBooking = updated) }
            }
        }
    }

    fun submitBookingCompletionReport(bookingId: String, report: BookingCompletionReport) {
        viewModelScope.launch {
            bookingRepository.submitCompletionReport(bookingId, report)
            bookingRepository.getBookingById(bookingId).firstOrNull()?.let { updated ->
                _uiState.update { it.copy(selectedBooking = updated) }
            }
        }
    }

    fun raiseDisputeOnBooking(bookingId: String, reason: String, evidenceList: List<String> = emptyList()) {
        viewModelScope.launch {
            val user = _uiState.value.currentUser
            bookingRepository.raiseDisputeWithEvidence(bookingId, user?.id ?: "usr_client", reason, evidenceList)
            bookingRepository.getBookingById(bookingId).firstOrNull()?.let { updated ->
                _uiState.update { it.copy(selectedBooking = updated) }
            }
            navigateTo(KaziHubDestination.DISPUTE_DETAIL)
        }
    }

    fun submitStructuredCreativeBooking(request: CreativeServiceBookingRequest) {
        viewModelScope.launch {
            val record = bookingRepository.createCreativeBookingRequest(request)
            _uiState.update { it.copy(selectedBooking = record) }
            navigateTo(KaziHubDestination.BOOKING_DETAIL)
        }
    }

    fun submitEquipmentRentalBooking(
        request: EquipmentRentalBookingRequest,
        onResult: (Boolean, String?) -> Unit
    ) {
        viewModelScope.launch {
            val res = bookingRepository.createEquipmentRentalBookingRequest(request)
            res.onSuccess { record ->
                _uiState.update { it.copy(selectedBooking = record) }
                onResult(true, null)
            }.onFailure { err ->
                onResult(false, err.message ?: "Gear reservation conflict.")
            }
        }
    }

    fun submitJobRequest(
        title: String,
        brief: String,
        budget: Long,
        currency: String,
        startDate: String,
        endDate: String,
        locationName: String
    ) {
        viewModelScope.launch {
            val user = _uiState.value.currentUser
            val request = JobRequest(
                id = "job_${System.currentTimeMillis()}",
                clientId = user?.id ?: "usr_client",
                clientName = user?.displayName ?: "Production Client",
                title = title,
                briefDescription = brief,
                requiredRole = CreativeCategory.CINEMATOGRAPHY,
                equipmentRequired = listOf("Standard Cinema Kit"),
                location = EastAfricaLocation(
                    id = "loc_custom",
                    country = _uiState.value.selectedCountry ?: EastAfricanCountry.TANZANIA,
                    primaryDivision = "Production Hub",
                    secondaryDivision = "Shoot District",
                    neighbourhoodOrArea = locationName
                ),
                startDate = startDate,
                endDate = endDate,
                estimatedBudget = budget,
                budgetCurrency = currency
            )
            bookingRepository.createJobRequest(request)
            navigateTo(KaziHubDestination.BOOKING_CENTER)
        }
    }

    // Support Actions
    fun createSupportTicket(
        category: SupportCategory,
        subject: String,
        description: String,
        relatedBookingRef: String?
    ) {
        viewModelScope.launch {
            val user = _uiState.value.currentUser ?: return@launch
            val newTicket = supportTicketRepository.createTicket(
                userId = user.id,
                userName = user.displayName,
                userEmail = user.email,
                category = category,
                subject = subject,
                description = description,
                relatedBookingRef = relatedBookingRef
            )
            _uiState.update { it.copy(selectedTicket = newTicket) }
            navigateTo(KaziHubDestination.SUPPORT_TICKET_DETAIL)
        }
    }

    fun replyToTicket(ticketId: String, content: String) {
        viewModelScope.launch {
            val user = _uiState.value.currentUser ?: return@launch
            supportTicketRepository.addTicketResponse(
                ticketId = ticketId,
                authorId = user.id,
                authorName = user.displayName,
                isStaff = false,
                message = content
            )
            supportTicketRepository.getTicketById(ticketId).firstOrNull()?.let { updated ->
                _uiState.update { it.copy(selectedTicket = updated) }
            }
        }
    }

    fun addEquipmentListing(
        title: String,
        brandModel: String,
        category: EquipmentCategory,
        dailyRate: Long,
        rentalTerms: String,
        serialNumber: String,
        onResult: (Boolean, String?) -> Unit
    ) {
        viewModelScope.launch {
            val user = _uiState.value.currentUser
            if (user == null) {
                onResult(false, "User must be authenticated to list gear.")
                return@launch
            }
            val location = user.primaryLocation ?: EastAfricaLocation(
                id = "loc_${user.country.name.lowercase()}",
                country = user.country,
                primaryDivision = "Production Hub",
                secondaryDivision = "Commercial District",
                neighbourhoodOrArea = "Equipment Dispatch Hub"
            )
            val gear = EquipmentItem(
                id = "eq_${System.currentTimeMillis()}",
                owner = EquipmentOwnerSummary(
                    ownerId = user.id,
                    name = user.displayName,
                    isVerifiedOwner = user.isIdentityVerified,
                    verifiedBadgeType = "Registered Owner"
                ),
                title = title,
                brandModel = brandModel,
                category = category,
                condition = EquipmentCondition.GOOD,
                photoUrls = emptyList(),
                dailyRate = dailyRate,
                currencyCode = user.country.currencyCode,
                location = location,
                availabilitystatus = AvailabilityState.AVAILABLE,
                technicalSpecs = emptyMap(),
                includedAccessories = emptyList(),
                terms = RentalTerms(
                    minimumRentalDays = 1,
                    securityDepositAmount = dailyRate / 2,
                    insuranceRequired = true,
                    operatorAccompaniedOnly = false,
                    standardContractRequired = true,
                    termsDocumentText = rentalTerms
                ),
                serialNumberHash = serialNumber
            )
            equipmentRepository.addEquipment(gear)
            onResult(true, null)
        }
    }

    fun updateSupportTicketStatus(ticketId: String, status: TicketStatus) {
        viewModelScope.launch {
            supportTicketRepository.updateTicketStatus(ticketId, status)
        }
    }

    // Admin User Moderation
    fun suspendUser(userId: String, reason: String) {
        viewModelScope.launch {
            userRepository.suspendUser(userId, reason)
        }
    }

    fun reactivateUser(userId: String) {
        viewModelScope.launch {
            userRepository.reactivateUser(userId)
        }
    }

    fun sendMessage(convId: String, content: String) {
        viewModelScope.launch {
            messageRepository.sendMessage(convId, content)
        }
    }

    fun markNotificationAsRead(id: String) {
        viewModelScope.launch {
            messageRepository.markNotificationAsRead(id)
        }
    }
}
